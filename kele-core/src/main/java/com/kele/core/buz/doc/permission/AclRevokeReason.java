package com.kele.core.buz.doc.permission;

/**
 * doc_file_folder_acl.revoke_reason 取值常量。
 * <p>
 * 设计：用 String 常量（不用 enum）是因为存到 DB 是 VARCHAR，避免在 entity / mapper / SQL 三处
 * 反复 valueOf / name() 转换。新增原因时直接加常量即可。
 */
public final class AclRevokeReason {

    private AclRevokeReason() {}

    /** 群组解散触发的级联撤销；restoreGroup 时只会复活这一类 */
    public static final String GROUP_DISSOLVE = "GROUP_DISSOLVE";

    /** 用户通过 revokeAcl API 手动撤销 */
    public static final String MANUAL = "MANUAL";

    /** grantAcl replace=true 时全量替换触发的撤销 */
    public static final String REPLACE = "REPLACE";
}
