package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.pojo.Danmu;
import com.waitfans.backend.service.danmu.DanmuService;
import com.waitfans.backend.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuControllerTest {
    @Test
    void readsPublishedDatabaseRowsAndRebuildsCache() {
        DanmuService service = mock(DanmuService.class);
        RedisUtil redis = mock(RedisUtil.class);
        Danmu expected = new Danmu();
        expected.setId(8);
        List<Danmu> published = Collections.singletonList(expected);
        when(service.getPublishedDanmuList(10)).thenReturn(published);
        DanmuController controller = controller(service, redis);

        CustomResponse response = controller.getDanmuList("10");

        assertSame(published, response.getData());
        verify(redis).addMember("danmu_idset:10", 8);
        verify(service, never()).getDanmuListByIdset(any());
    }

    @Test
    void redisOutageDoesNotHidePublishedDanmu() {
        DanmuService service = mock(DanmuService.class);
        RedisUtil redis = mock(RedisUtil.class);
        List<Danmu> published = Collections.singletonList(new Danmu());
        when(service.getPublishedDanmuList(10)).thenReturn(published);
        doThrow(new IllegalStateException("redis down")).when(redis).addMember(eq("danmu_idset:10"), any());
        DanmuController controller = controller(service, redis);

        CustomResponse response = controller.getDanmuList("10");

        assertSame(published, response.getData());
        verify(redis).addMember(eq("danmu_idset:10"), any());
    }

    @Test
    void staleNonEmptyCacheCannotHideNewlyPublishedDanmu() {
        DanmuService service = mock(DanmuService.class);
        RedisUtil redis = mock(RedisUtil.class);
        Danmu oldDanmu = new Danmu();
        oldDanmu.setId(1);
        Danmu acknowledgedDanmu = new Danmu();
        acknowledgedDanmu.setId(2);
        List<Danmu> completeList = java.util.Arrays.asList(oldDanmu, acknowledgedDanmu);
        when(service.getPublishedDanmuList(10)).thenReturn(completeList);
        DanmuController controller = controller(service, redis);

        CustomResponse response = controller.getDanmuList("10");

        assertSame(completeList, response.getData());
        verify(redis).addMember("danmu_idset:10", 1);
        verify(redis).addMember("danmu_idset:10", 2);
        verify(redis, never()).getMembers(any());
        verify(service, never()).getDanmuListByIdset(any());
    }

    private DanmuController controller(DanmuService service, RedisUtil redis) {
        DanmuController controller = new DanmuController();
        ReflectionTestUtils.setField(controller, "danmuService", service);
        ReflectionTestUtils.setField(controller, "redisUtil", redis);
        return controller;
    }
}
