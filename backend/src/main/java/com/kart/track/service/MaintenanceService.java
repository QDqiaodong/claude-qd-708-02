package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Kart;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.MaintenanceDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.MaintenanceRepository;

/**
 * 模块五：维修保养（待修 → 维修中 → 已完工，总费用 = 工时 × 工时费 + 配件费）。
 *
 * <p>工单状态与车辆状态是一条链：同一辆车同时只允许一张未结工单（待修/维修中），
 * 车辆只有在「不存在任何未结工单」时才允许回到可用。开单/开工/完工都先按
 * 车辆行加悲观锁再校验，把同车的并发写串行化，重复点击或并发操作只会得到
 * 明确的中文提示，不会留下工单与车辆状态相互矛盾的数据。
 */
@Service
public class MaintenanceService {

    private final MaintenanceRepository maintenanceRepository;
    private final KartRepository kartRepository;

    public MaintenanceService(MaintenanceRepository maintenanceRepository, KartRepository kartRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.kartRepository = kartRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.MaintenanceView> list() {
        Map<Long, Kart> karts = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            karts.put(kart.getId(), kart);
        }
        List<Views.MaintenanceView> list = new ArrayList<>();
        for (Maintenance item : maintenanceRepository.findAllByOrderByStartTimeDesc()) {
            list.add(toView(item, karts.get(item.getKartId())));
        }
        return list;
    }

    /** 费用统计：已完工单的配件费 / 工时费 / 总费用 */
    @Transactional(readOnly = true)
    public Views.MaintSummary summary() {
        List<Maintenance> all = maintenanceRepository.findAll();
        BigDecimal partsFee = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        long waiting = 0;
        long repairing = 0;
        long done = 0;
        for (Maintenance item : all) {
            if ("WAITING".equals(item.getStatus())) {
                waiting += 1;
            } else if ("REPAIRING".equals(item.getStatus())) {
                repairing += 1;
            } else if ("DONE".equals(item.getStatus())) {
                done += 1;
                partsFee = partsFee.add(nz(item.getPartsFee()));
                totalFee = totalFee.add(nz(item.getTotalFee()));
            }
        }
        BigDecimal labor = totalFee.subtract(partsFee);
        return new Views.MaintSummary(all.size(), waiting, repairing, done, partsFee, labor, totalFee);
    }

    /** 开单：同一辆车已有未结工单（待修/维修中）时拒绝，避免多工单并存搞乱车辆状态 */
    @Transactional
    public Views.MaintenanceView create(MaintenanceDtos.MaintSaveReq req) {
        if (!TrackDict.isValidMaintType(req.getMaintType())) {
            throw new BizException("维修类型不合法");
        }
        // 先锁车辆行：同车的开单/开工/完工/转状态在此串行，随后的校验读到的都是已提交的最新数据
        Kart kart = kartRepository.findByIdForUpdate(req.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        if (maintenanceRepository.existsByOrderNo(req.getOrderNo())) {
            throw new BizException("维修单号 " + req.getOrderNo() + " 已存在");
        }
        List<Maintenance> open = openOrdersOf(kart.getId());
        if (!open.isEmpty()) {
            Maintenance first = open.get(0);
            throw new BizException(kart.getKartNo() + " 已有未结维修单 " + first.getOrderNo()
                    + "（" + TrackDict.maintStatusName(first.getStatus()) + "），同一辆车同时只能存在一张未结工单，"
                    + "请先将其开工并完工");
        }
        BigDecimal hours = req.getWorkHours() == null ? BigDecimal.ONE : req.getWorkHours();
        BigDecimal parts = req.getPartsFee() == null ? BigDecimal.ZERO : req.getPartsFee();
        BigDecimal total = hours.multiply(TrackDict.LABOR_RATE).add(parts);

        Maintenance item = new Maintenance();
        item.setOrderNo(req.getOrderNo());
        item.setKartId(kart.getId());
        item.setMaintType(req.getMaintType());
        item.setFaultDesc(req.getFaultDesc());
        item.setWorkHours(hours);
        item.setPartsFee(parts);
        item.setTotalFee(total);
        item.setWorker(req.getWorker());
        item.setStatus("WAITING");
        item.setStartTime(LocalDateTime.now());
        item.setEndTime(null);
        return toView(maintenanceRepository.save(item), kart);
    }

    /** 开工：待修 → 维修中，车辆置为维修；同车已有维修中工单时拒绝（一辆车同时只能修一张单） */
    @Transactional
    public Views.MaintenanceView start(Long id, MaintenanceDtos.WorkerReq req) {
        // 先锁工单再锁车辆（全链路统一加锁顺序）：并发开工在后者事务里读到最新状态后被明确拒绝
        Maintenance item = maintenanceRepository.findByIdForUpdate(id).orElseThrow(() -> new BizException("维修单不存在"));
        if (!"WAITING".equals(item.getStatus())) {
            throw new BizException("维修单 " + item.getOrderNo() + " 当前为「"
                    + TrackDict.maintStatusName(item.getStatus()) + "」，只有「待修」的维修单可以开工");
        }
        Kart kart = kartRepository.findByIdForUpdate(item.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        if ("ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 正在赛道上，不能开工维修");
        }
        for (Maintenance other : openOrdersOf(kart.getId())) {
            if (!other.getId().equals(item.getId()) && "REPAIRING".equals(other.getStatus())) {
                throw new BizException(kart.getKartNo() + " 的维修单 " + other.getOrderNo()
                        + " 正在维修中，同一辆车同时只能开工一张工单");
            }
        }
        if (req.getWorker() != null && !req.getWorker().isBlank()) {
            item.setWorker(req.getWorker());
        }
        if (item.getWorker() == null || item.getWorker().isBlank()) {
            throw new BizException("请填写维修人");
        }
        item.setStatus("REPAIRING");
        item.setStartTime(LocalDateTime.now());
        maintenanceRepository.save(item);

        // 车辆置为维修；停用车保持停用（是否恢复可用由完工/车辆页按未结工单情况决定）
        if (!"MAINTENANCE".equals(kart.getStatus()) && !"DISABLED".equals(kart.getStatus())) {
            kart.setStatus("MAINTENANCE");
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }
        return toView(item, kart);
    }

    /** 完工：维修中 → 已完工；确认该车不存在其他未结工单后才把车辆恢复为可用，保养类项目重置「上次保养日期」 */
    @Transactional
    public Views.MaintenanceView complete(Long id) {
        Maintenance item = maintenanceRepository.findByIdForUpdate(id).orElseThrow(() -> new BizException("维修单不存在"));
        if (!"REPAIRING".equals(item.getStatus())) {
            throw new BizException("维修单 " + item.getOrderNo() + " 当前为「"
                    + TrackDict.maintStatusName(item.getStatus()) + "」，只有「维修中」的维修单可以完工");
        }
        Kart kart = kartRepository.findByIdForUpdate(item.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        item.setStatus("DONE");
        item.setEndTime(LocalDateTime.now());
        maintenanceRepository.save(item);

        // 关键校验：同车还有其他未结工单时车辆必须保持维修，最后一张工单完工才恢复可用
        long remaining = openOrdersOf(kart.getId()).stream().filter(o -> !o.getId().equals(item.getId())).count();
        if (remaining == 0 && "MAINTENANCE".equals(kart.getStatus())) {
            kart.setStatus("AVAILABLE");
            kart.setUpdatedAt(LocalDateTime.now());
        }
        if (TrackDict.isServiceType(item.getMaintType())) {
            kart.setLastServiceDate(LocalDate.now());
            kart.setUpdatedAt(LocalDateTime.now());
        }
        kartRepository.save(kart);
        return toView(item, kart);
    }

    /** 同车未结工单（待修 + 维修中），按开始时间升序；调用前需已持有该车辆行锁 */
    private List<Maintenance> openOrdersOf(Long kartId) {
        return maintenanceRepository.findByKartIdAndStatusInOrderByStartTimeAsc(kartId, TrackDict.MAINT_OPEN_STATUSES);
    }

    Views.MaintenanceView toView(Maintenance item, Kart kart) {
        long minutes = 0;
        if (item.getStartTime() != null && item.getEndTime() != null) {
            minutes = ChronoUnit.MINUTES.between(item.getStartTime(), item.getEndTime());
        }
        String kartStatus = kart == null ? "" : kart.getStatus();
        return new Views.MaintenanceView(
                item.getId(),
                item.getOrderNo(),
                item.getKartId(),
                kart == null ? "" : kart.getKartNo(),
                kartStatus,
                TrackDict.kartStatusName(kartStatus),
                item.getMaintType(),
                TrackDict.maintTypeName(item.getMaintType()),
                item.getFaultDesc(),
                item.getWorkHours(),
                item.getPartsFee(),
                item.getTotalFee(),
                item.getWorker(),
                item.getStartTime(),
                item.getEndTime(),
                item.getStatus(),
                TrackDict.maintStatusName(item.getStatus()),
                minutes);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
