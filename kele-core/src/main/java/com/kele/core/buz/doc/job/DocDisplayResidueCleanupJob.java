package com.kele.core.buz.doc.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.service.IDocFileContentStorageService;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

/**
 * #18: 清理 createFile / copyFile 失败留下的 DISPLAY 残留孤儿。
 *
 * 这些行 status 永远停在 DISPLAY(-2)，用户列表里查不到，
 * 但 DB 行 + 存储内容真实存在，长期累积浪费空间。
 *
 * 策略：扫 status=DISPLAY && createAt < now - threshold（默认 24h），
 * best-effort 清存储内容（仅 FILE 类型），单事务删 DB 行 + ACL。
 *
 * 阈值设计：正常流程几秒内就从 DISPLAY 翻 NORMAL，
 * 能撑过 threshold 的几乎确定是孤儿，不会误删正在创建中的文件。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "kele.doc.cleanup.display.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DocDisplayResidueCleanupJob {

    @Autowired
    private IDocFileFolderService docFileFolderService;

    @Autowired
    private IDocFileContentStorageService docFileContentStorageService;

    @Autowired
    private DocFileFolderAclMapper docFileFolderAclMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${kele.doc.cleanup.display.threshold-hours:24}")
    private int thresholdHours;

    @Scheduled(cron = "${kele.doc.cleanup.display.cron:0 0 3 * * ?}")
    public void cleanup() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(thresholdHours);
        List<DocFileFolder> residue = docFileFolderService.list(
            Wrappers.<DocFileFolder>lambdaQuery()
                .eq(DocFileFolder::getStatus, DelStatusEnum.DISPLAY.getStatus())
                .lt(DocFileFolder::getCreateAt, threshold)
        );
        if (CollectionUtils.isEmpty(residue)) {
            log.info("DISPLAY 残留清理：本轮无残留");
            return;
        }
        List<Long> ids = residue.stream().map(DocFileFolder::getId).collect(Collectors.toList());
        log.info("DISPLAY 残留清理：发现 {} 条孤儿，thresholdHours={}, ids={}",
            ids.size(), thresholdHours, ids);

        // best-effort 清存储内容（仅 FILE 类型有存储）
        residue.stream()
            .filter(f -> FileFolderFormatEnum.FILE.getFormat().equals(f.getFormat()))
            .forEach(f -> {
                try {
                    docFileContentStorageService.delete(f);
                } catch (Exception e) {
                    log.error("DISPLAY 残留清理：清理存储失败 folderId={}", f.getId(), e);
                }
            });

        // 单事务删 DB 行 + ACL（防御性：DISPLAY 文件理论无 ACL，但保险清一遍）
        transactionTemplate.execute(status -> {
            docFileFolderAclMapper.delete(Wrappers.<DocFileFolderAcl>lambdaQuery()
                .in(DocFileFolderAcl::getFolderId, ids));
            docFileFolderService.removeByIds(ids);
            return null;
        });
        log.info("DISPLAY 残留清理：完成，共清理 {} 条", ids.size());
    }
}
