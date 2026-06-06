package com.kele.core.buz.doc.ao.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.model.vo.DocFileFolderResVO;
import com.kele.core.buz.doc.permission.PermissionService;
import com.kele.core.buz.doc.service.IDocFileContentStorageService;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.doc.service.IDocRecycleService;
import com.kele.core.buz.doc.service.IDocRelationLevelService;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.util.Collections;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DocFileFolderAO 单测。v0.7 §6.2 BUG B 修复验证：
 * {@code getFolderTree} / {@code getAllFolderTree} 在 owner 条件上 OR 上 ACL 命中子查询，
 * 让"被分享的 folder"也出现在 tree 中。
 *
 * <p>核心断言：抓 list() 收到的 {@link LambdaQueryWrapper}，验 SQL 含 {@code OR}
 * （旧实现只有一串 AND，新实现带 OR ACL 子查询）。这是最具针对性的 regression guard：
 * 旧 SQL 没 OR → 修前这条测试 fail；新 SQL 带 OR → 修后通过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocFileFolderAO tree 列表 — 共享 folder 可见性（v0.7 BUG B 修复）")
class DocFileFolderAOImplTest {

    @Mock private IDocFileFolderService docFileFolderService;
    @Mock private IDocRecycleService docRecycleService;
    @Mock private IDocRelationLevelService docRelationLevelService;
    @Mock private IDocFileContentStorageService docFileContentStorageService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private PermissionService permissionService;

    @InjectMocks private DocFileFolderAOImpl docFileFolderAO;

    /**
     * 手动 init MyBatis-Plus lambda cache。生产环境 Spring 启动时
     * {@code MybatisPlusInterceptor} 会自动扫描 @TableName/@TableField 把 entity
     * 注册进 cache；unit test 没 Spring context 所以要手动注册，否则子查询
     * {@code Wrappers.<DocFileFolderAcl>lambdaQuery()} 会抛
     * "can not find lambda cache for this entity"。
     *
     * <p>注：kele-doc 用的是 mybatis-plus 3.5.3.1，这个版本
     * {@code TableInfoHelper.initTableInfo(MapperBuilderAssistant, Class<?>)}
     * 只有 2-arg 重载（没有 4-arg 带 GlobalConfig 的）。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        Configuration config = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(config, "testResource");
        TableInfoHelper.initTableInfo(assistant, DocFileFolder.class);
        TableInfoHelper.initTableInfo(assistant, DocFileFolderAcl.class);
        TableInfoHelper.initTableInfo(assistant, DocRecycle.class);
    }

    @BeforeEach
    void setUp() {
        UserInfoBO bo = new UserInfoBO();
        bo.setId(200L);
        bo.setAccount("u200");
        bo.setRole("USER");
        LoginContext.setUserInfo(bo);
        // v0.7 spec §4.1：当前用户加入的群组 id 列表；空 list 也行
        // （getMyGroupIds() → LoginContext.getUserGroupIds() → 此处空 list）
        LoginContext.setUserGroupIds(Collections.<Long>emptyList());
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
    }

    @Test
    @DisplayName("getFolderTree: SQL 包含 OR 子句（owner OR ACL 命中）")
    void getFolderTree_sqlContainsAclOrSubquery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DocFileFolder>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(docFileFolderService.list(captor.capture()))
            .thenReturn(Collections.<DocFileFolder>emptyList());

        // 触发
        List<DocFileFolderResVO> result = docFileFolderAO.getFolderTree(
            0L, FileFolderFormatEnum.FOLDER.getFormat());
        // 触发后 list() 必须被调过一次（用 captor 间接保证）
        assertNotNull(captor.getValue(), "list() 必须被调用");

        // 验证 SQL 包含 OR —— 旧实现 .eq(ownerId) 一串 AND，无 OR
        LambdaQueryWrapper<DocFileFolder> wrapper = captor.getValue();
        String sql = wrapper.getExpression().getSqlSegment();
        assertNotNull(sql, "wrapper 必须产出 SQL");
        assertTrue(sql.toUpperCase().contains(" OR "),
            "getFolderTree SQL 必须包含 OR 子句（owner OR ACL 命中），实际 SQL: " + sql);
    }

    @Test
    @DisplayName("getAllFolderTree: SQL 包含 OR 子句（owner OR ACL 命中）")
    void getAllFolderTree_sqlContainsAclOrSubquery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DocFileFolder>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(docFileFolderService.list(captor.capture()))
            .thenReturn(Collections.<DocFileFolder>emptyList());

        // 触发
        Object result = docFileFolderAO.getAllFolderTree();
        assertNotNull(captor.getValue(), "list() 必须被调用");

        LambdaQueryWrapper<DocFileFolder> wrapper = captor.getValue();
        String sql = wrapper.getExpression().getSqlSegment();
        assertNotNull(sql, "wrapper 必须产出 SQL");
        assertTrue(sql.toUpperCase().contains(" OR "),
            "getAllFolderTree SQL 必须包含 OR 子句（owner OR ACL 命中），实际 SQL: " + sql);
    }

    @Test
    @DisplayName("getAccessibleFolders: SQL 包含 OR 子句（owner OR ACL 命中，不限 parent）")
    void getAccessibleFolders_sqlContainsAclOrSubquery() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DocFileFolder>> captor =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(docFileFolderService.list(captor.capture()))
            .thenReturn(Collections.<DocFileFolder>emptyList());

        // 触发
        java.util.List<com.kele.core.buz.doc.model.vo.DocFileFolderResVO> result =
            docFileFolderAO.getAccessibleFolders();
        assertNotNull(captor.getValue(), "list() 必须被调用");

        LambdaQueryWrapper<DocFileFolder> wrapper = captor.getValue();
        String sql = wrapper.getExpression().getSqlSegment();
        assertNotNull(sql, "wrapper 必须产出 SQL");
        assertTrue(sql.toUpperCase().contains(" OR "),
            "getAccessibleFolders SQL 必须包含 OR 子句（owner OR ACL 命中），实际 SQL: " + sql);
    }

    @Test
    @DisplayName("getAccessibleFolders: isOwner 标志正确（owner=true，shared=false）")
    void getAccessibleFolders_isOwnerFlagCorrect() {
        // 模拟：test 自己的 root (folderId=5, owner=200) + admin 分享的 folder (folderId=7, owner=100)
        DocFileFolder myRoot = new DocFileFolder();
        myRoot.setId(5L);
        myRoot.setName("我的文件");
        myRoot.setParentId(0L);
        myRoot.setOwnerId(200L);  // 我 owner
        myRoot.setFormat(FileFolderFormatEnum.FOLDER.getFormat());
        myRoot.setStatus(1);
        myRoot.setFolderCount(0);

        DocFileFolder shared = new DocFileFolder();
        shared.setId(7L);
        shared.setName("456");
        shared.setParentId(6L);  // 父级 chain 上无我的 ACL（孤儿授权场景）
        shared.setOwnerId(100L);  // 别人 owner
        shared.setFormat(FileFolderFormatEnum.FOLDER.getFormat());
        shared.setStatus(1);
        shared.setFolderCount(0);

        // 模拟 file（应该被过滤掉）
        DocFileFolder fileRow = new DocFileFolder();
        fileRow.setId(99L);
        fileRow.setName("123.md");
        fileRow.setParentId(5L);
        fileRow.setOwnerId(200L);
        fileRow.setFormat(FileFolderFormatEnum.FILE.getFormat());
        fileRow.setStatus(1);
        fileRow.setFolderCount(0);

        when(docFileFolderService.list(any(LambdaQueryWrapper.class)))
            .thenReturn(java.util.Arrays.asList(myRoot, shared, fileRow));

        // 触发
        java.util.List<com.kele.core.buz.doc.model.vo.DocFileFolderResVO> result =
            docFileFolderAO.getAccessibleFolders();

        // 验证：file 被过滤掉，只返 2 个 folder
        assertNotNull(result);
        assertTrue(result.size() == 2,
            "应该返 2 个 folder（file 被过滤），实际 " + result.size());

        // 验证 isOwner 标志
        com.kele.core.buz.doc.model.vo.DocFileFolderResVO myVo = result.stream()
            .filter(v -> v.getId().equals(5L)).findFirst().orElse(null);
        com.kele.core.buz.doc.model.vo.DocFileFolderResVO sharedVo = result.stream()
            .filter(v -> v.getId().equals(7L)).findFirst().orElse(null);

        assertNotNull(myVo, "我的 root 应该在结果里");
        assertNotNull(sharedVo, "分享的 folder 应该在结果里");

        assertTrue(Boolean.TRUE.equals(myVo.getIsOwner()),
            "我 owner 的 folder isOwner 应为 true");
        assertTrue(Boolean.FALSE.equals(sharedVo.getIsOwner()),
            "别人分享给我的 folder isOwner 应为 false");
    }
}
