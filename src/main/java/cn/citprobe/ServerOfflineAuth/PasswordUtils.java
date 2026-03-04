package cn.citprobe.ServerOfflineAuth;

public class PasswordUtils {
    public static String hashPassword(String plainPassword) {
        try {
            int factor = Config.SERVER.passwordHashWorkFactor.get();
            return PasswordHash.hashpw(plainPassword, PasswordHash.gensalt(factor));
        } catch (Throwable t) {
            ServerOfflineAuth.LOGGER.error("密码哈希失败", t);
            throw new RuntimeException("密码哈希失败", t);
        }
    }

    public static boolean checkPassword(String plainPassword, String hashed) {
        return PasswordHash.checkpw(plainPassword, hashed);
    }
}
