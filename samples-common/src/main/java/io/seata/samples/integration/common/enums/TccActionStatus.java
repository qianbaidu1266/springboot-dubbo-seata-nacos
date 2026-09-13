package io.seata.samples.integration.common.enums;

/**
 * TCC 事务控制表的状态机。
 *
 * <pre>
 *   (无记录) --Try--> TRIED --Confirm--> CONFIRMED
 *                        \--Cancel --> CANCELED
 *   (无记录) --Cancel(空回滚)--> CANCELED   ← 同时挡住迟到的 Try（悬挂）
 * </pre>
 *
 * @author lidong
 */
public enum TccActionStatus {

    /** Try 已执行，资源已预留 */
    TRIED(1, "TRIED", "Try 已预留"),

    /** Confirm 已执行，资源已真正扣减 */
    CONFIRMED(2, "CONFIRMED", "Confirm 已提交"),

    /** Cancel 已执行（含空回滚），资源已释放或从未占用 */
    CANCELED(3, "CANCELED", "Cancel 已回滚");

    private final int code;
    private final String name;
    private final String desc;

    TccActionStatus(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 反查，未知 code 返回 null
     */
    public static TccActionStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (TccActionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
