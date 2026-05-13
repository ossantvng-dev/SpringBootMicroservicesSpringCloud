package com.photoapp.accounts.configuration;

import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.entity.Account;
import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountMapperConfig {

    @Bean
    public ModelMapper accountModelMapper(ModelMapper modelMapper) {
        modelMapper.typeMap(CreateAccountInputDTO.class, Account.class)
                .addMappings(mapper -> {
                    mapper.skip(Account::setId);
                    mapper.skip(Account::setVersion);
                    mapper.skip(Account::setCreatedAt);
                    mapper.skip(Account::setUpdatedAt);
                });
        return modelMapper;
    }

}

