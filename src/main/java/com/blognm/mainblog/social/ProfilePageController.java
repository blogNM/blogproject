package com.blognm.mainblog.social;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 회원 프로필과 내 활동은 /members/{번호}. 본인이면 같은 화면에서 공개 설정을 고친다 (D-91). */
@Controller
public class ProfilePageController {

    @GetMapping("/members/{id}")
    public String profile(@PathVariable long id) {
        return "forward:/profile.html";
    }
}
