package com.blognm.mainblog.blog;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 블로그 주소는 /blog/{주소} (D-70). 화면은 정적 HTML이고, 화면의 JS가 주소에서 블로그를 읽는다. */
@Controller
public class BlogPageController {

    @GetMapping("/blog/{slug}")
    public String home(@PathVariable String slug) {
        return "forward:/blog.html";
    }

    @GetMapping("/blog/{slug}/manage")
    public String manage(@PathVariable String slug) {
        return "forward:/blog-manage.html";
    }
}
