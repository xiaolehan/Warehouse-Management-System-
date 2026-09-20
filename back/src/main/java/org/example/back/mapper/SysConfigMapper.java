package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.back.entity.SysConfig;

public interface SysConfigMapper extends BaseMapper<SysConfig> {

    /**
     * D108：复活被逻辑删除的参数行（@TableLogic 会给 Wrapper 自动追加 is_deleted=0，
     * 普通 update 触达不到软删行，故用原生 SQL）。
     */
    @Update("UPDATE sys_config SET is_deleted = 0, config_value = #{value}, " +
            "config_name = #{name}, remark = #{remark} " +
            "WHERE config_key = #{key}")
    int reviveLogicalDeletedRow(@Param("key") String key,
                                @Param("value") String value,
                                @Param("name") String name,
                                @Param("remark") String remark);
}
