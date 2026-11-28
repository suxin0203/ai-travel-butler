package com.suxin.travel.traveljava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.suxin.travel.traveljava.entity.ScheduleItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ScheduleItemMapper extends BaseMapper<ScheduleItem> {

    /**
     * 状态机 CAS：只有当前状态等于 expect 时才更新为 to。
     * 提醒链路的幂等与防重复投递全部依赖这个原子的"状态迁移"，而不是 Redis SETNX——
     * 原因：SETNX 在发送前置位会把失败后的重试也当成"已消费"吞掉。
     *
     * @return 影响行数：1=迁移成功；0=状态已被别人改过（放弃本次操作）
     */
    @Update("UPDATE schedule_item SET status = #{to} WHERE id = #{id} AND status = #{expect}")
    int casStatus(@Param("id") Long id, @Param("expect") String expect, @Param("to") String to);

    /**
     * 行程删除的级联取消（契约 §4.3a）：单条 UPDATE 批量取消未触发的日程。
     * 为什么用单条 SQL 而不是"先查再逐条改"：与扫描器的 CAS(PENDING→DISPATCHED) 交错时，
     * 先查后改会把刚被扫描器推进状态的日程也覆盖掉，计数和状态都会错。
     */
    @Update("UPDATE schedule_item SET status = 'CANCELLED' "
            + "WHERE trip_id = #{tripId} AND user_id = #{uid} AND status IN ('PENDING','DISPATCHED')")
    int cancelUntriggeredByTrip(@Param("tripId") Long tripId, @Param("uid") Long uid);
}
