package com.schemaforge.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthUser {
    private String id;
    private String email;
}
