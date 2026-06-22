package cn.nuaa.jensonxu.fairy.common.repository.mysql;

import cn.nuaa.jensonxu.fairy.common.repository.mysql.data.QqUserBindingDO;
import cn.nuaa.jensonxu.fairy.common.repository.mysql.mapper.QqUserBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class QqUserBindingRepository {

    private final QqUserBindingMapper mapper;

    /**
     * 按 QQ 号查询有效绑定（过滤软删除）
     * @param qqNumber QQ 号
     * @return 绑定记录，未绑定时为 Optional.empty()
     */
    public Optional<QqUserBindingDO> findByQqNumber(String qqNumber) {
        return Optional.ofNullable(
                mapper.selectOne(
                        new LambdaQueryWrapper<QqUserBindingDO>()
                                .eq(QqUserBindingDO::getQqNumber, qqNumber)
                                .eq(QqUserBindingDO::getIsDeleted, 0)
                )
        );
    }
}