package com.waitfans.backend.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CarouselItem {
    private Integer id;
    private String url;
    private String title;
    private String color;
    private String target;
}
