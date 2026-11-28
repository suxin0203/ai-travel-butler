package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.TripSaveDTO;
import com.suxin.travel.traveljava.service.TripService;
import com.suxin.travel.traveljava.vo.TripConfirmVO;
import com.suxin.travel.traveljava.vo.TripDetailVO;
import com.suxin.travel.traveljava.vo.TripSaveVO;
import com.suxin.travel.traveljava.vo.TripVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 行程接口（契约 §4.1~§4.3） */
@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    @PostMapping
    public Result<TripSaveVO> save(@Valid @RequestBody TripSaveDTO dto) {
        return Result.ok(tripService.save(UserContext.requireUid(), dto));
    }

    @GetMapping
    public Result<List<TripVO>> list() {
        return Result.ok(tripService.list(UserContext.requireUid()));
    }

    @GetMapping("/{id}")
    public Result<TripDetailVO> detail(@PathVariable long id) {
        return Result.ok(tripService.detail(UserContext.requireUid(), id));
    }

    @PostMapping("/{id}/confirm")
    public Result<TripConfirmVO> confirm(@PathVariable long id) {
        return Result.ok(tripService.confirm(UserContext.requireUid(), id));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    public Result<com.suxin.travel.traveljava.vo.TripCancelVO> cancel(@PathVariable long id) {
        return Result.ok(tripService.cancelTrip(UserContext.requireUid(), id));
    }
}
