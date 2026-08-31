package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.Result;
import org.example.back.dto.QcDisposeDTO;
import org.example.back.dto.QcSaveDTO;
import org.example.back.service.QcService;
import org.example.back.vo.QcStateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/business/qc")
public class QcController {

    @Autowired
    private QcService qcService;

    /** 录入一条测试结果 */
    @PostMapping("/record")
    @PreventDuplicateSubmit(message = "请勿重复提交质检结果")
    public Result<Void> record(@Valid @RequestBody QcSaveDTO dto) {
        qcService.record(dto);
        return Result.success();
    }

    /** 处置 NG（返工 REWORK / 报废 SCRAP） */
    @PostMapping("/dispose")
    @PreventDuplicateSubmit(message = "请勿重复处置")
    public Result<Void> dispose(@Valid @RequestBody QcDisposeDTO dto) {
        qcService.dispose(dto);
        return Result.success();
    }

    /** 订单质检状态快照 + 记录历史 */
    @GetMapping("/order/{orderId}")
    public Result<QcStateVO> snapshot(@PathVariable Long orderId) {
        return Result.success(qcService.snapshot(orderId));
    }
}