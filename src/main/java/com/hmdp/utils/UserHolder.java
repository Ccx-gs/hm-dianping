package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    // 1. 泛型改成 UserDTO
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    // 2. 参数改成 UserDTO
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    // 3. 返回值改成 UserDTO
    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}