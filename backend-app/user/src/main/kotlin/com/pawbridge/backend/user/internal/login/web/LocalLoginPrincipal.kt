package com.pawbridge.backend.user.internal.login.web

import com.pawbridge.backend.user.internal.login.application.LocalLogin
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

@JvmRecord
data class LocalLoginPrincipal(val account: LocalLogin.Account) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority(account.role))

    override fun getPassword(): String? = account.password

    override fun getUsername(): String? = account.email

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = true

    override fun toString(): String {
        return "LocalLoginPrincipal"
    }
}
