package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.MaintenanceDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.MaintenanceService;

import jakarta.validation.Valid;

/** 模块五：维修保养。 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping
    public ApiResponse<List<Views.MaintenanceView>> list() {
        return ApiResponse.success(maintenanceService.list());
    }

    /** 费用统计：已完工单的配件费 / 工时费 / 总费用 */
    @GetMapping("/summary")
    public ApiResponse<Views.MaintSummary> summary() {
        return ApiResponse.success(maintenanceService.summary());
    }

    @PostMapping
    public ApiResponse<Views.MaintenanceView> create(@Valid @RequestBody MaintenanceDtos.MaintSaveReq req) {
        return ApiResponse.success("维修单已开立", maintenanceService.create(req));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Views.MaintenanceView> start(@PathVariable("id") Long id,
                                                    @RequestBody MaintenanceDtos.WorkerReq req) {
        Views.MaintenanceView view = maintenanceService.start(id, req);
        String message = "MAINTENANCE".equals(view.kartStatus()) ? "已开工，车辆转入维修状态" : "已开工";
        return ApiResponse.success(message, view);
    }

    /** 完工：该车无其他未结工单时车辆恢复可用，否则保持维修并明确提示 */
    @PostMapping("/{id}/complete")
    public ApiResponse<Views.MaintenanceView> complete(@PathVariable("id") Long id) {
        Views.MaintenanceView view = maintenanceService.complete(id);
        String message;
        if ("MAINTENANCE".equals(view.kartStatus())) {
            message = "已完工；该车还有其他未结维修单，车辆保持「维修」状态";
        } else if ("AVAILABLE".equals(view.kartStatus())) {
            message = "已完工，车辆恢复可用";
        } else {
            message = "已完工";
        }
        return ApiResponse.success(message, view);
    }
}
