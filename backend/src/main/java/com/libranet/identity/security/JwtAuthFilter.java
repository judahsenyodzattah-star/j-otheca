package com.libranet.identity.security;

import com.libranet.common.web.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Isolation layer 1 of 9: validates the access token and authenticates the GLOBAL identity only.
 * NOTE: the JWT deliberately carries no tenant authority — library access is validated
 * per-request by TenantResolutionFilter against the memberships table.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() > 8) {
            try {
                Jws<Claims> jws = jwtService.parse(header.substring(7));
                Claims claims = jws.getPayload();
                if ("access".equals(claims.get("typ", String.class))) {
                    String role = claims.get("role", String.class);
                    AuthUser principal = new AuthUser(claims.getSubject(), claims.get("email", String.class), role);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // invalid/expired token → request continues unauthenticated; entry point returns 401 where required
            }
        }
        chain.doFilter(request, response);
    }
}
