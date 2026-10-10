package com.jotheca.identity.service;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.dto.UpdateProfileRequest;
import com.jotheca.identity.dto.UserDto;
import com.jotheca.identity.model.User;
import com.jotheca.identity.repo.UserRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final AuditService audit;

    public UserService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UserDto me() {
        return UserDto.from(current());
    }

    @Transactional
    public UserDto updateProfile(UpdateProfileRequest req) {
        User user = current();
        if (req.fullName() != null && !req.fullName().isBlank()) user.setFullName(req.fullName().trim());
        if (req.phone() != null) user.setPhone(req.phone());
        audit.log(AuditAction.PROFILE_UPDATED, "User", user.getId(), Map.of());
        return UserDto.from(user);
    }

    private User current() {
        return users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication required"));
    }
}
