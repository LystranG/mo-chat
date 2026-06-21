package com.github.lystran.mochat.call.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.github.lystran.mochat.call.mapper.CallOfflineNotificationMapper;
import com.github.lystran.mochat.call.mapper.FriendshipMapper;
import com.github.lystran.mochat.call.mapper.GroupMemberMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

/** MyBatis-Plus 配置。 */
@Factory
public class MyBatisPlusConfig {

    @Singleton
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("default", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultExecutorType(org.apache.ibatis.session.ExecutorType.REUSE);

        configuration.addMapper(CallOfflineNotificationMapper.class);
        configuration.addMapper(FriendshipMapper.class);
        configuration.addMapper(GroupMemberMapper.class);

        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
