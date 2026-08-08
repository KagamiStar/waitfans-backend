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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuControllerTest {
    @Test
    void fallsBackToPublishedDatabaseRowsWhenCacheSetIsMissing() {
        DanmuService service = mock(DanmuService.class);
        RedisUtil redis = mock(RedisUtil.class);
        Danmu expected = new Danmu();
        expected.setId(8);
        List<Danmu> published = Collections.singletonList(expected);
        when(redis.getMembers("danmu_idset:10")).thenReturn(Collections.emptySet());
        when(service.getDanmuListByIdset(any())).thenReturn(null);
        when(service.getPublishedDanmuList(10)).thenReturn(published);
        DanmuController controller = controller(service, redis);

        CustomResponse response = controller.getDanmuList("10");

        assertSame(published, response.getData());
        verify(redis).addMember("danmu_idset:10", 8);
    }

    @Test
    void redisOutageDoesNotHidePublishedDanmu() {
        DanmuService service = mock(DanmuService.class);
        RedisUtil redis = mock(RedisUtil.class);
        List<Danmu> published = Collections.singletonList(new Danmu());
        when(redis.getMembers("danmu_idset:10")).thenThrow(new IllegalStateException("redis down"));
        when(service.getPublishedDanmuList(10)).thenReturn(published);
        DanmuController controller = controller(service, redis);

        CustomResponse response = controller.getDanmuList("10");

        assertSame(published, response.getData());
        verify(redis).addMember(eq("danmu_idset:10"), any());
    }

    private DanmuController controller(DanmuService service, RedisUtil redis) {
        DanmuController controller = new DanmuController();
        ReflectionTestUtils.setField(controller, "danmuService", service);
        ReflectionTestUtils.setField(controller, "redisUtil", redis);
        return controller;
    }
}
