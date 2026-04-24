package com.photoapp.commons.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {

    private Long id;
    private String username;
    private String email;
    private Boolean activeUser;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<AccountDTO> accounts;

}
