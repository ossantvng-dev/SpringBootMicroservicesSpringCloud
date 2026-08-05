package com.photoapp.test.support.security;

import com.photoapp.security.model.CustomUserPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public class WithMockPhotoAppUserSecurityContextFactory
        implements WithSecurityContextFactory<WithMockPhotoAppUser> {

    @Override
    public SecurityContext createSecurityContext(WithMockPhotoAppUser annotation) {
        // Empty string is the only way to express "absent" in an annotation attribute,
        // since annotation defaults cannot be null.
        String userId = annotation.userId().isEmpty() ? null : annotation.userId();

        CustomUserPrincipal principal =
                TestPrincipals.principal(userId, annotation.username(), annotation.roles());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestPrincipals.authentication(principal));
        return context;
    }
}
