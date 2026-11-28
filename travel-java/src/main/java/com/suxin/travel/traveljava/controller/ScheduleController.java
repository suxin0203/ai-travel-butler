package com.suxin.travel.traveljava.controller;

import com.suxin.travel.traveljava.common.Result;
import com.suxin.travel.traveljava.common.UserContext;
import com.suxin.travel.traveljava.dto.ScheduleCreateDTO;
import com.suxin.travel.traveljava.dto.ScheduleUpdateDTO;
import com.suxin.travel.traveljava.service.ScheduleService;
import com.suxin.travel.traveljava.vo.ScheduleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 日程 CRUD（API 契约 §3.4~§3.8），越权防护在 ScheduleService 统一处理 */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    public Result<List<ScheduleVO>> list(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) String status) {
        return Result.ok(scheduleService.list(UserContext.requireUid(), from, to, status));
    }

    @PostMapping
    public Result<ScheduleVO> create(@Valid @RequestBody ScheduleCreateDTO dto) {
        return Result.ok(scheduleService.create(UserContext.requireUid(), dto, "MANUAL"));
    }

    @PutMapping("/{id}")
    public Result<ScheduleVO> update(@PathVariable long id, @Valid @RequestBody ScheduleUpdateDTO dto) {
        return Result.ok(scheduleService.update(UserContext.requireUid(), id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        scheduleService.delete(UserContext.requireUid(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/complete")
    public Result<Void> complete(@PathVariable long id) {
        scheduleService.complete(UserContext.requireUid(), id);
        return Result.ok();
    }

    @PostMapping("/{id}/reopen")
    public Result<Void> reopen(@PathVariable long id) {
        scheduleService.reopen(UserContext.requireUid(), id);
        return Result.ok();
    }

    @PostMapping("/cleanup")
    public Result<java.util.Map<String, Integer>> cleanup() {
        int cleaned = scheduleService.cleanup(UserContext.requireUid());
        return Result.ok(java.util.Map.of("cleaned", cleaned));
    }
}
