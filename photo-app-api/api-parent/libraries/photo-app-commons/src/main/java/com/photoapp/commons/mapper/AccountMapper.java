package com.photoapp.commons.mapper;

import com.photoapp.commons.dto.account.AccountDTO;
import com.photoapp.commons.dto.account.AccountTypeDTO;
import com.photoapp.commons.dto.account.CreateAccountInputDTO;
import com.photoapp.entity.Account;
import com.photoapp.entity.AccountType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AccountMapper {

    /*
        The DTO calls it accountTypeDTO while the entity calls it accountType, so the
        pairing has to be stated. Under ModelMapper this silently produced null and the
        account type never reached the API.
     */
    @Mapping(source = "accountType", target = "accountTypeDTO")
    AccountDTO toDTO(Account account);

    /*
        Built through Lombok's builder, so the BaseEntity fields are unreachable by
        construction. activeAccount is left to its @Builder.Default of true.
     */
    @Mapping(source = "accountTypeDTO", target = "accountType")
    @Mapping(target = "activeAccount", ignore = true)
    Account toEntity(CreateAccountInputDTO createAccountInputDTO);

    AccountTypeDTO toDTO(AccountType accountType);

    AccountType toEntity(AccountTypeDTO accountTypeDTO);

}
