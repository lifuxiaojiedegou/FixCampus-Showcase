package com.fixcampus.form;

import java.util.Locale;
import java.util.Objects;
import com.fixcampus.validation.CodePointLength;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class RegisterForm {
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "[a-z0-9_]{4,30}", message = "用户名需为 4～30 位英文字母、数字或下划线")
    private String username;

    @NotNull(message = "请输入密码")
    @CodePointLength(min = 8, max = 64, message = "密码需为 8～64 个字符")
    private String password;

    @NotNull(message = "请再次输入密码")
    @CodePointLength(min = 8, max = 64, message = "确认密码需为 8～64 个字符")
    private String confirmPassword;

    @NotBlank(message = "请输入姓名")
    @CodePointLength(min = 1, max = 50, message = "姓名需为 1～50 个字符")
    private String realName;

    @CodePointLength(max = 30, message = "联系方式不能超过 30 个字符")
    private String phone;

    @AssertTrue(message = "两次输入的密码不一致")
    public boolean isPasswordConfirmed() { return Objects.equals(password, confirmPassword); }

    public String getUsername() { return username; }
    public void setUsername(String value) { username = value == null ? null : value.strip().toLowerCase(Locale.ROOT); }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String value) { confirmPassword = value; }
    public String getRealName() { return realName; }
    public void setRealName(String value) { realName = value == null ? null : value.strip(); }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value == null || value.isBlank() ? null : value.strip(); }

    public void clearPasswords() {
        password = null;
        confirmPassword = null;
    }
}
