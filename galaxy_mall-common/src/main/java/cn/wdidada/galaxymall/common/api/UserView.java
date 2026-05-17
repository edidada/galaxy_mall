package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class UserView implements Serializable {

    private String userId;
    private String nickname;
    private int level;

    public UserView() {
    }

    public UserView(String userId, String nickname, int level) {
        this.userId = userId;
        this.nickname = nickname;
        this.level = level;
    }

    public String userId() {
        return userId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String nickname() {
        return nickname;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public int level() {
        return level;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }
}
