package io.seata.samples.integration.common.util;

import java.math.BigDecimal;

import org.apache.seata.rm.tcc.api.BusinessActionContext;

/**
 * TCC 分支上下文取值工具。
 *
 * <p>Try 阶段用 {@code @BusinessActionContextParameter} 挂上去的业务参数，
 * 会被序列化上报到 TC，二阶段的 Confirm / Cancel 再从 TC 回传的上下文里取回来。
 * 经过一次序列化/反序列化之后，数值类型可能发生退化（例如 {@code Integer} 变成
 * {@code Double}/{@code String}），所以这里统一按「字符串 → BigDecimal → 目标类型」
 * 转换，避免直接强转踩 ClassCastException。</p>
 *
 * @author lidong
 */
public final class TccContextUtil {

    private TccContextUtil() {
    }

    /**
     * 取字符串参数
     */
    public static String getString(BusinessActionContext context, String key) {
        Object value = getRaw(context, key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取整型参数
     */
    public static Integer getInteger(BusinessActionContext context, String key) {
        Object value = getRaw(context, key);
        return value == null ? null : new BigDecimal(String.valueOf(value)).intValue();
    }

    /**
     * 取浮点参数
     */
    public static Double getDouble(BusinessActionContext context, String key) {
        Object value = getRaw(context, key);
        return value == null ? null : new BigDecimal(String.valueOf(value)).doubleValue();
    }

    /**
     * 当前调用是否处于全局事务的 TCC 分支中。
     *
     * <p>只有 {@code true} 时才存在分支意义上的幂等/空回滚/悬挂问题；
     * 诸如直接打 Controller 做单服务自测这种没有全局事务的调用，退化为普通本地调用。</p>
     */
    public static boolean inGlobalBranch(BusinessActionContext context) {
        return context != null && context.getXid() != null;
    }

    /**
     * 分支标识，供日志使用：xid / branchId / actionName
     */
    public static String describe(BusinessActionContext context) {
        if (context == null) {
            return "context=null";
        }
        return "xid=" + context.getXid()
                + ", branchId=" + context.getBranchId()
                + ", actionName=" + context.getActionName();
    }

    private static Object getRaw(BusinessActionContext context, String key) {
        if (context == null) {
            return null;
        }
        return context.getActionContext(key);
    }
}
