package org.example.back.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量删除尽力而为结果（2026-09-23 手测问题 1 定案）：
 * 逐行执行与单删相同的校验，能删的删，失败的聚合返回明细。
 */
@Data
public class BatchDeleteResultVO {

    private Integer successCount = 0;

    private Integer failureCount = 0;

    private List<FailureItem> failures = new ArrayList<>();

    public void addSuccess() {
        this.successCount++;
    }

    public void addFailure(Long id, String name, String reason) {
        this.failureCount++;
        FailureItem item = new FailureItem();
        item.setId(id);
        item.setName(name);
        item.setReason(reason);
        this.failures.add(item);
    }

    @Data
    public static class FailureItem {
        private Long id;
        private String name;
        private String reason;
    }
}
