package com.loopers.infrastructure.user;

import com.loopers.domain.user.PasswordEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PasswordEncryptorImpl implements PasswordEncryptor {

    private final PasswordEncoder passwordEncoder;

    @Override
    public String encrypt(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
