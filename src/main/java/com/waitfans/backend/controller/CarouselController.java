package com.waitfans.backend.controller;

import com.waitfans.backend.pojo.CustomResponse;
import com.waitfans.backend.repository.CarouselRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CarouselController {
    @Autowired
    private CarouselRepository carouselRepository;

    @GetMapping("/carousel/list")
    public CustomResponse list() {
        return new CustomResponse(200, "OK", carouselRepository.findEnabled());
    }
}
