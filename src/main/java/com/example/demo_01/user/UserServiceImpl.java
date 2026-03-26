package com.example.demo_01.user;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.user.constant.UserConstant;
import com.example.demo_01.user.mapper.UserMapper;
import com.example.demo_01.user.model.dto.UserAddRequest;
import com.example.demo_01.user.model.dto.UserLoginRequest;
import com.example.demo_01.user.model.dto.UserQueryRequest;
import com.example.demo_01.user.model.dto.UserRegisterRequest;
import com.example.demo_01.user.model.dto.UserUpdateRequest;
import com.example.demo_01.user.model.entity.User;
import com.example.demo_01.user.model.enums.UserRoleEnum;
import com.example.demo_01.user.model.vo.LoginUserVO;
import com.example.demo_01.user.model.vo.UserVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

import static com.mybatisflex.core.query.QueryMethods.column;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final int ACCOUNT_MIN_LENGTH = 4;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int MAX_PAGE_SIZE = 50;

    private final BCryptPasswordEncoder passwordEncoder;

    public UserServiceImpl(BCryptPasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String userRegister(UserRegisterRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "request body is required");
        }
        String userAccount = normalizeAccount(request.getUserAccount());
        String userPassword = normalizePassword(request.getUserPassword());
        String checkPassword = request.getCheckPassword();
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "passwords do not match");
        }
        ensureAccountAvailable(userAccount, null);

        OffsetDateTime now = OffsetDateTime.now();
        User user = new User();
        user.setUserId(UUID.randomUUID().toString());
        user.setUsername(userAccount);
        user.setUserAccount(userAccount);
        user.setUserPassword(passwordEncoder.encode(userPassword));
        user.setUserName(resolveUserName(request.getUserName(), userAccount));
        user.setUserRole(UserConstant.USER_ROLE);
        user.setEditTime(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setIsDelete(0);
        getMapper().insertUser(user);
        return user.getUserId();
    }

    @Override
    public LoginUserVO userLogin(UserLoginRequest request, HttpServletRequest httpServletRequest) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "request body is required");
        }
        String userAccount = normalizeAccount(request.getUserAccount());
        String userPassword = normalizePassword(request.getUserPassword());

        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(column("user_account").eq(userAccount))
                .and(column("is_delete").eq(0));
        User user = getMapper().selectOneByQuery(queryWrapper);
        if (user == null || StrUtil.isBlank(user.getUserPassword())
                || !passwordEncoder.matches(userPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "invalid account or password");
        }

        LoginUserVO loginUserVO = getLoginUserVO(user);
        httpServletRequest.getSession(true).setAttribute(UserConstant.USER_LOGIN_STATE, loginUserVO);
        return loginUserVO;
    }

    @Override
    public LoginUserVO getCurrentLoginUser(HttpServletRequest request) {
        return getLoginUserVO(getLoginUser(request));
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required");
        }
        Object sessionValue = session.getAttribute(UserConstant.USER_LOGIN_STATE);
        if (!(sessionValue instanceof LoginUserVO loginUserVO) || StrUtil.isBlank(loginUserVO.getUserId())) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required");
        }
        User user = getById(loginUserVO.getUserId());
        if (user == null || user.getIsDelete() != null && user.getIsDelete() == 1) {
            session.removeAttribute(UserConstant.USER_LOGIN_STATE);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required");
        }
        return user;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }
        session.removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }

    @Override
    public String addUser(UserAddRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "request body is required");
        }
        String userAccount = normalizeAccount(request.getUserAccount());
        String userPassword = normalizePassword(request.getUserPassword());
        ensureAccountAvailable(userAccount, null);

        OffsetDateTime now = OffsetDateTime.now();
        User user = new User();
        user.setUserId(UUID.randomUUID().toString());
        user.setUsername(userAccount);
        user.setUserAccount(userAccount);
        user.setUserPassword(passwordEncoder.encode(userPassword));
        user.setUserName(resolveUserName(request.getUserName(), userAccount));
        user.setUserAvatar(trimToNull(request.getUserAvatar()));
        user.setUserProfile(trimToNull(request.getUserProfile()));
        user.setUserRole(resolveUserRole(request.getUserRole(), UserConstant.USER_ROLE));
        user.setEditTime(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setIsDelete(0);
        getMapper().insertUser(user);
        return user.getUserId();
    }

    @Override
    public UserVO getUserById(String userId) {
        if (StrUtil.isBlank(userId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId is required");
        }
        User user = getById(userId.trim());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "user not found");
        }
        return getUserVO(user);
    }

    @Override
    public boolean updateUser(UserUpdateRequest request) {
        if (request == null || StrUtil.isBlank(request.getUserId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId is required");
        }
        User existing = getById(request.getUserId().trim());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "user not found");
        }
        if (StrUtil.isNotBlank(request.getUserAccount())) {
            String normalizedAccount = normalizeAccount(request.getUserAccount());
            ensureAccountAvailable(normalizedAccount, existing.getUserId());
            existing.setUserAccount(normalizedAccount);
            existing.setUsername(normalizedAccount);
        }
        if (request.getUserName() != null) {
            existing.setUserName(resolveUserName(request.getUserName(), existing.getUserAccount()));
        }
        if (request.getUserAvatar() != null) {
            existing.setUserAvatar(trimToNull(request.getUserAvatar()));
        }
        if (request.getUserProfile() != null) {
            existing.setUserProfile(trimToNull(request.getUserProfile()));
        }
        if (request.getUserRole() != null) {
            existing.setUserRole(resolveUserRole(request.getUserRole(), existing.getUserRole()));
        }
        OffsetDateTime now = OffsetDateTime.now();
        existing.setEditTime(now);
        existing.setUpdatedAt(now);
        return getMapper().updateUserProfile(existing) > 0;
    }

    @Override
    public boolean deleteUser(String userId) {
        if (StrUtil.isBlank(userId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId is required");
        }
        User existing = getById(userId.trim());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "user not found");
        }
        existing.setEditTime(OffsetDateTime.now());
        return removeById(existing.getUserId());
    }

    @Override
    public Page<UserVO> listUserByPage(UserQueryRequest request) {
        UserQueryRequest effectiveRequest = request == null ? new UserQueryRequest() : request;
        long pageNum = effectiveRequest.getPageNum() <= 0 ? 1 : effectiveRequest.getPageNum();
        long pageSize = effectiveRequest.getPageSize() <= 0 ? 10 : Math.min(effectiveRequest.getPageSize(), MAX_PAGE_SIZE);
        QueryWrapper queryWrapper = buildQueryWrapper(effectiveRequest);
        Page<User> userPage = page(new Page<>(pageNum, pageSize), queryWrapper);
        Page<UserVO> result = new Page<>(userPage.getPageNumber(), userPage.getPageSize());
        result.setTotalPage(userPage.getTotalPage());
        result.setTotalRow(userPage.getTotalRow());
        result.setRecords(userPage.getRecords().stream().map(this::getUserVO).toList());
        return result;
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserConstant.ADMIN_ROLE.equals(user.getUserRole());
    }

    private QueryWrapper buildQueryWrapper(UserQueryRequest request) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where(column("is_delete").eq(0));
        if (StrUtil.isNotBlank(request.getUserAccount())) {
            queryWrapper.and(column("user_account").like(request.getUserAccount().trim()));
        }
        if (StrUtil.isNotBlank(request.getUserName())) {
            queryWrapper.and(column("user_name").like(request.getUserName().trim()));
        }
        if (StrUtil.isNotBlank(request.getUserRole())) {
            queryWrapper.and(column("user_role").eq(request.getUserRole().trim()));
        }

        String sortColumn = resolveSortColumn(request.getSortField());
        QueryColumn orderColumn = column(sortColumn);
        if (isAscending(request.getSortOrder())) {
            queryWrapper.orderBy(orderColumn.asc());
        } else {
            queryWrapper.orderBy(orderColumn.desc());
        }
        return queryWrapper;
    }

/**
 * 解析排序字段，将前端传递的排序字段转换为数据库对应的字段名
 * @param sortField 前端传递的排序字段
 * @return 返回数据库对应的字段名，如果前端传递的字段不在允许的列表中，则返回默认的"created_at"
 */
    private String resolveSortColumn(String sortField) {
        // 定义允许的排序字段映射关系，将前端字段映射为数据库字段
        Map<String, String> allowedSortFields = Map.of(
                "userAccount", "user_account",    // 用户账号字段映射
                "userName", "user_name",          // 用户名字段映射
                "userRole", "user_role",          // 用户角色字段映射
                "createdAt", "created_at",        // 创建时间字段映射
                "updatedAt", "updated_at"         // 更新时间字段映射
        );
        if (sortField == null || sortField.isBlank()){
            return "created_at";
        }

        // 返回映射后的字段名，如果找不到对应的映射则返回默认的created_at
        return allowedSortFields.getOrDefault(sortField, "created_at");
    }

    private boolean isAscending(String sortOrder) {
        return "asc".equalsIgnoreCase(sortOrder) || "ascend".equalsIgnoreCase(sortOrder);
    }

    private void ensureAccountAvailable(String userAccount, String excludeUserId) {
        User existing = getMapper().selectOneByMap(Map.of("user_account", userAccount));
        if (existing != null && !existing.getUserId().equals(excludeUserId)) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "user account already exists");
        }
    }

    private String normalizeAccount(String userAccount) {
        if (StrUtil.isBlank(userAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userAccount is required");
        }
        String normalized = userAccount.trim();
        if (normalized.length() < ACCOUNT_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userAccount length must be at least 4");
        }
        return normalized;
    }

    private String normalizePassword(String userPassword) {
        if (StrUtil.isBlank(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userPassword is required");
        }
        String normalized = userPassword.trim();
        if (normalized.length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userPassword length must be at least 8");
        }
        return normalized;
    }

    private String resolveUserRole(String userRole, String defaultRole) {
        if (StrUtil.isBlank(userRole)) {
            return defaultRole;
        }
        UserRoleEnum roleEnum = UserRoleEnum.fromValue(userRole.trim());
        if (roleEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userRole is invalid");
        }
        return roleEnum.getValue();
    }

    private String resolveUserName(String userName, String fallbackAccount) {
        String normalized = trimToNull(userName);
        return normalized == null ? fallbackAccount : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
