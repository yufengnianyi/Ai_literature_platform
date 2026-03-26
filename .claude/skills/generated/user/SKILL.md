---
name: user
description: "Skill for the User area of demo_01. 78 symbols across 14 files."
---

# User

78 symbols | 14 files | Cohesion: 80%

## When to Use

- Working with code in `src/`
- Understanding how User, UserVO, LoginUserVO work
- Modifying user-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | userRegister, userLogin, addUser, updateUser, deleteUser (+17) |
| `src/main/java/com/example/demo_01/user/model/entity/User.java` | User, getUserId, setUsername, getUserAccount, getUserPassword (+16) |
| `src/main/java/com/example/demo_01/user/UserService.java` | userRegister, userLogin, getCurrentLoginUser, deleteUser, listUserByPage (+5) |
| `src/main/java/com/example/demo_01/user/UserController.java` | register, login, getLoginUser, deleteUser, listUserByPage (+4) |
| `src/test/java/com/example/demo_01/user/UserControllerTest.java` | shouldRegisterUser, shouldLoginUser, shouldGetCurrentLoginUser, shouldListUsersByPageForAdmin, shouldDeleteUserForAdmin |
| `src/main/java/com/example/demo_01/user/AdminUserInitializer.java` | initAdminUserRunner, isBlank |
| `src/main/java/com/example/demo_01/user/mapper/UserMapper.java` | insertUser, updateUserProfile |
| `src/main/java/com/example/demo_01/user/model/enums/UserRoleEnum.java` | fromValue |
| `src/main/java/com/example/demo_01/user/model/vo/UserVO.java` | UserVO |
| `src/main/java/com/example/demo_01/user/model/vo/LoginUserVO.java` | LoginUserVO |

## Entry Points

Start here when exploring this area:

- **`User`** (Class) — `src/main/java/com/example/demo_01/user/model/entity/User.java:11`
- **`UserVO`** (Class) — `src/main/java/com/example/demo_01/user/model/vo/UserVO.java:8`
- **`LoginUserVO`** (Class) — `src/main/java/com/example/demo_01/user/model/vo/LoginUserVO.java:8`
- **`UserRegisterRequest`** (Class) — `src/main/java/com/example/demo_01/user/model/dto/UserRegisterRequest.java:7`
- **`UserQueryRequest`** (Class) — `src/main/java/com/example/demo_01/user/model/dto/UserQueryRequest.java:7`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `User` | Class | `src/main/java/com/example/demo_01/user/model/entity/User.java` | 11 |
| `UserVO` | Class | `src/main/java/com/example/demo_01/user/model/vo/UserVO.java` | 8 |
| `LoginUserVO` | Class | `src/main/java/com/example/demo_01/user/model/vo/LoginUserVO.java` | 8 |
| `UserRegisterRequest` | Class | `src/main/java/com/example/demo_01/user/model/dto/UserRegisterRequest.java` | 7 |
| `UserQueryRequest` | Class | `src/main/java/com/example/demo_01/user/model/dto/UserQueryRequest.java` | 7 |
| `UserLoginRequest` | Class | `src/main/java/com/example/demo_01/user/model/dto/UserLoginRequest.java` | 7 |
| `UserDeleteRequest` | Class | `src/main/java/com/example/demo_01/user/model/dto/UserDeleteRequest.java` | 7 |
| `UserServiceImpl` | Class | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 33 |
| `UserService` | Interface | `src/main/java/com/example/demo_01/user/UserService.java` | 14 |
| `userRegister` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 46 |
| `userLogin` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 75 |
| `addUser` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 136 |
| `updateUser` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 175 |
| `deleteUser` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 208 |
| `isAdmin` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 255 |
| `buildQueryWrapper` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 260 |
| `resolveSortColumn` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 283 |
| `isAscending` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 294 |
| `ensureAccountAvailable` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 298 |
| `normalizeAccount` | Method | `src/main/java/com/example/demo_01/user/UserServiceImpl.java` | 305 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Append → GetUserId` | cross_community | 5 |
| `UpdateUser → GetUserId` | intra_community | 3 |
| `ListUserByPage → GetUserAccount` | cross_community | 3 |
| `ListUserByPage → GetUserName` | cross_community | 3 |
| `ListUserByPage → GetUserRole` | cross_community | 3 |
| `ListUserByPage → ResolveSortColumn` | cross_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Conversation | 2 calls |
| Service | 1 calls |

## How to Explore

1. `gitnexus_context({name: "User"})` — see callers and callees
2. `gitnexus_query({query: "user"})` — find related execution flows
3. Read key files listed above for implementation details
