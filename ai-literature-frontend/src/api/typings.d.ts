declare namespace API {
  type chatParams = {
    conversationId?: string;
    memory_id?: number;
    prompt: string;
  };

  type ServerSentEventString = true;

  type LoginUserVO = {
    userId: string;
    userAccount: string;
    userName: string;
    userAvatar?: string;
    userProfile?: string;
    userRole: string;
    createdAt?: string;
    updatedAt?: string;
  };

  type UserVO = {
    userId: string;
    userAccount: string;
    userName: string;
    userAvatar?: string;
    userProfile?: string;
    userRole: string;
    createdAt?: string;
    updatedAt?: string;
  };

  type UserRegisterRequest = {
    userAccount: string;
    userPassword: string;
    checkPassword: string;
    userName?: string;
  };

  type UserLoginRequest = {
    userAccount: string;
    userPassword: string;
  };

  type UserDeleteRequest = {
    userId: string;
  };

  type UserQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    userAccount?: string;
    userName?: string;
    userRole?: string;
  };

  type PageUserVO = {
    pageNumber: number;
    pageSize: number;
    totalPage: number;
    totalRow: number;
    records: UserVO[];
  };
}
