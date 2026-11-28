package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.TravelRequestDTO;
import com.suxin.travel.traveljava.service.TravelService;
import com.suxin.travel.traveljava.vo.TravelRecommendVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 行程推荐与对话接口，契约见 docs/API契约.md §2.6 与 §3 */
@Slf4j
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travelService;

    @PostMapping("/recommend")
    public Result<TravelRecommendVO> recommend(@Valid @RequestBody TravelRequestDTO dto) {
        log.info("行程推荐请求 uid={} city={} days={} budget={}",
                UserContext.requireUid(), dto.getCity(), dto.getDays(), dto.getBudget());
        TravelRecommendVO vo = travelService.recommend(dto);
        log.info("行程推荐完成 uid={} city={} days={}", UserContext.requireUid(), vo.getCity(), vo.getDays());
        return Result.ok(vo);
    }
}
