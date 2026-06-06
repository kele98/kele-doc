package com.kele.core.other.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();

        // 单节点配置
        String address = "redis://" + properties.getHost() + ":" + properties.getPort();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(properties.getDatabase())
                .setTimeout(5000)
                .setConnectionPoolSize(10) // 总连接池大小
                .setConnectionMinimumIdleSize(5); // 最小空闲连接数

        if (!StringUtils.isEmpty(properties.getPassword())) {
            serverConfig.setPassword(properties.getPassword());
        }

        return Redisson.create(config);
    }

    @Bean
    public com.kele.common.util.RedissonLock redisLock(RedissonClient redissonClient) {
        return new com.kele.common.util.RedissonLock(redissonClient);
    }
}