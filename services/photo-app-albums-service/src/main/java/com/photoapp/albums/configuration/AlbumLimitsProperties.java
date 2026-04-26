package com.photoapp.albums.configuration;

import com.photoapp.commons.dto.account.AccountType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "photoapp.albums.limits")
public class AlbumLimitsProperties {

    private int basic;

    public int getLimitForAccountType(AccountType accountType) {
        if (accountType == AccountType.BASIC) {
            return basic;
        }
        return -1;
    }
}
