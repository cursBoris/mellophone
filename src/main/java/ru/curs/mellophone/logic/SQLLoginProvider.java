package ru.curs.mellophone.logic;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;


/**
 * Конфигурация подключения к SQL-серверу.
 */
public final class SQLLoginProvider extends AbstractLoginProvider {

    private static final String USER = "Пользователь '";
    private static final String USER_LOGIN = "Логин пользователя '";
    private static final String ERROR_SQL_SERVER = "Ошибка при работе с базой '%s': %s. Запрос: '%s'";

    private static final String USER_IS_BLOCKED_PERMANENTLY = "User %s is blocked permanently.";

    private static final String PASSWORD_DIVIDER = "#";

    private static final String PBKDF2 = "pbkdf2";
    private static final String PBKDF2_PASSWORD_DIVIDER = "\\$";
    private static final String PBKDF2_ALG_DIVIDER = ":";

    private static ConcurrentHashMap<String, MessageDigest> mdPool = new ConcurrentHashMap<String, MessageDigest>(4);
    private final HashMap<String, String> searchReturningAttributes = new HashMap<String, String>();
    private HikariDataSource dataSource = null;
    private Properties hikariProperties = new Properties();
    private String connectionUsername;
    private String connectionPassword;
    private String table;
    private String fieldLogin;
    private String fieldPassword;
    private String fieldBlocked = null;
    private String hashAlgorithm = "SHA-256";
    private String localSecuritySalt = "";
    private String procPostProcess = null;
    private AuthMethod authMethod = AuthMethod.CHECK;

    /**
     * Возвращает тип SQL сервера.
     */
    private static SQLServerType getSQLServerType(String url) {
        final String mssql = "sqlserver";
        final String postgresql = "postgresql";
        final String oracle = "oracle";
        final String firebird = "firebird";

        SQLServerType st = null;
        if (url.indexOf(mssql) > -1) {
            st = SQLServerType.MSSQL;
        } else {
            if (url.indexOf(postgresql) > -1) {
                st = SQLServerType.POSTGRESQL;
            } else {
                if (url.indexOf(oracle) > -1) {
                    st = SQLServerType.ORACLE;
                } else {
                    if (url.indexOf(firebird) > -1) {
                        st = SQLServerType.FIREBIRD;
                    }
                }
            }
        }

        return st;
    }

    private static void checkForPossibleSQLInjection(String sql, String errMsg) throws EAuthServerLogic {
        if (sql.indexOf(" ") > -1) throw EAuthServerLogic.create(errMsg);
    }

    @Override
    void setupLogger(boolean isLogging) {
        if (isLogging) {
            setLogger(LoggerFactory.getLogger(SQLLoginProvider.class));
        }
    }

    void addHikariProperty(String name, String value) {
        hikariProperties.put(name, value);
    }

    void setConnectionUsername(String connectionUsername) {
        this.connectionUsername = connectionUsername;
    }

    void setConnectionPassword(String connectionPassword) {
        this.connectionPassword = connectionPassword;
    }

    void setTable(String table) {
        // this.table = table;
        this.table = table.replace(".", "\".\"");
    }

    void setFieldLogin(String fieldLogin) {
        this.fieldLogin = fieldLogin;
    }

    void setFieldPassword(String fieldPassword) {
        this.fieldPassword = fieldPassword;
    }

    void setFieldBlocked(String fieldBlocked) {
        this.fieldBlocked = fieldBlocked;
    }

    void setHashAlgorithm(String hashAlgorithm) {
        this.hashAlgorithm = hashAlgorithm;
    }

    void setLocalSecuritySalt(String localSecuritySalt) {
        this.localSecuritySalt = localSecuritySalt;
    }

    void setProcPostProcess(String procPostProcess) {
        this.procPostProcess = procPostProcess;
    }

    @Override
    void addReturningAttributes(String name, String value) {
        searchReturningAttributes.put(name, value);
    }

    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod;
    }

    private synchronized Connection getConnection() throws SQLException {
        if (dataSource == null) {
            HikariConfig hikariConfig = new HikariConfig(hikariProperties);
            if (nonNull(getConnectionUrl())) {
                hikariConfig.setJdbcUrl(getConnectionUrl());
            }
            if (nonNull(connectionUsername)) {
                hikariConfig.setUsername(connectionUsername);
            }
            if (isNull(hikariConfig.getDataSourceClassName()) && nonNull(connectionPassword)) {
                hikariConfig.setPassword(connectionPassword);
            }
            dataSource = new HikariDataSource(hikariConfig);
        }

        return dataSource.getConnection();
    }

    @Override
    void connect(String sesid, String login, String password, String ip, ProviderContextHolder context, PrintWriter pw) throws EAuthServerLogic {

        if (getLogger() != null) {
            getLogger().info("Url='" + getConnectionUrl() + "'");
            getLogger().info("login='" + login + "'");
        }

        checkForPossibleSQLInjection(login, USER_LOGIN + login + "' в '" + getConnectionUrl() + "' не успешен");

        boolean success = false;
        String message = "";
        String sql = "";
        BadLoginType blt = BadLoginType.BAD_CREDENTIALS;
        try {

            if (authMethod == AuthMethod.CONNECT) {
                Connection connAuthMethodCONNECT = DataSourceBuilder.create().url(getConnectionUrl()).build().getConnection(login, password);
                connAuthMethodCONNECT.close();
            }

            ((SQLLink) context).conn = getConnection();

            if (getSQLServerType(getConnectionUrl()) == SQLServerType.FIREBIRD) {

                switch (authMethod) {
                    case CHECK:
                        sql = String.format("SELECT \"%s\", %s FROM \"%s\" WHERE \"%s\" = ?", fieldPassword, getSelectFields(), table, fieldLogin);
                        break;
                    case CONNECT:
                        sql = String.format("SELECT %s FROM \"%s\" WHERE \"%s\" = ?", getSelectFields(), table, fieldLogin);
                        break;
                    default:
                        break;
                }

            } else {
                sql = String.format("SELECT \"%s\", %s FROM \"%s\" WHERE lower(\"%s\") = ?", fieldPassword, getSelectFields(), table, fieldLogin);
            }


            PreparedStatement stat = ((SQLLink) context).conn.prepareStatement(sql);

            if (getSQLServerType(getConnectionUrl()) == SQLServerType.FIREBIRD) {
                stat.setString(1, login);
            } else {
                stat.setString(1, login.toLowerCase());
            }


            boolean hasResult = stat.execute();
            if (hasResult) {
                ResultSet rs = stat.getResultSet();
                if (rs.next()) {

                    if ((procPostProcess == null) && (fieldBlocked != null)) {
                        if (rs.getBoolean(fieldBlocked)) {
                            success = false;
                            message = String.format(USER_IS_BLOCKED_PERMANENTLY, login);
                            blt = BadLoginType.USER_BLOCKED_PERMANENTLY;
                        }
                    }

                    if (blt != BadLoginType.USER_BLOCKED_PERMANENTLY) {

                        switch (authMethod) {
                            case CHECK:
                                String pwdComplex = rs.getString(fieldPassword);
                                success = (pwdComplex != null) && ((!AuthManager.getTheManager().isCheckPasswordHashOnly()) && pwdComplex.equals(password) || checkPasswordHash(pwdComplex, password));
                                break;
                            case CONNECT:
                                success = true;
                                break;
                            default:
                                break;
                        }

                        StringWriter sw = new StringWriter();
                        writeReturningAttributes(sw, rs);
                        sw.flush();

                        if (procPostProcess != null) {

                            PostProcessResult ppr = callProcPostProcess(((SQLLink) context).conn, sesid, login, success, sw.toString(), ip, false, LockoutManager.getLockoutManager().getAttemptsCount(login) + 1, LockoutManager.getLockoutTime() * 60);
                            success = success && ppr.isSuccess();
                            message = ppr.getMessage();

                        } else {
                            if (success) {
                                message = USER_LOGIN + login + "' в '" + getConnectionUrl() + "' успешен!";
                            }
                        }


                        if (success && (pw != null)) {
                            //writeReturningAttributes(pw, rs);
                            pw.append(sw.toString());
                        }

                    }


                } else {

                    if (procPostProcess != null) {

                        PostProcessResult ppr = callProcPostProcess(((SQLLink) context).conn, sesid, login, false, null, ip, false, LockoutManager.getLockoutManager().getAttemptsCount(login) + 1, LockoutManager.getLockoutTime() * 60);

                        message = ppr.getMessage();

                    }

                }
            }
        } catch (Exception e) {
            if (getLogger() != null) {
                getLogger().error(String.format(ERROR_SQL_SERVER, getConnectionUrl(), e.getMessage(), sql));
            }
            throw EAuthServerLogic.create(e);
        }

        if (!success && message.isEmpty()) {
            message = USER_LOGIN + login + "' в '" + getConnectionUrl() + "' не успешен: " + BAD_CREDENTIALS;
        }

        if (getLogger() != null) {
            getLogger().info(message);
        }

        if (!success) {
            EAuthServerLogic eas = EAuthServerLogic.create(message);
            eas.setBadLoginType(blt);
            throw eas;
        }

    }

    private void writeReturningAttributes(Writer writer, ResultSet rs) throws XMLStreamException, FactoryConfigurationError, SQLException {
        String[] attrs = searchReturningAttributes.keySet().toArray(new String[0]);
        XMLStreamWriter xw = XMLOutputFactory.newInstance().createXMLStreamWriter(writer);

        xw.writeStartDocument("utf-8", "1.0");
        xw.writeEmptyElement("user");
        for (String attr : attrs) {
            writeXMLAttr(xw, attr, rs.getString(searchReturningAttributes.get(attr)));
        }
        xw.writeEndDocument();
        xw.flush();
    }

    public PostProcessResult callProcPostProcess(Connection conn, String sesid, String login, boolean isauth, String attributes, String ip, boolean islocked, int attemptsCount, long timeToUnlock) throws SQLException {

        if (conn == null) {
            conn = getConnection();
        }

        CallableStatement cs = conn.prepareCall(String.format("{? = call %s (?, ?, ?, ?, ?, ?, ?, ?, ?)}", procPostProcess));

        cs.registerOutParameter(1, Types.INTEGER);
        cs.setString(2, sesid);
        cs.setString(3, login);
        cs.setBoolean(4, isauth);
        cs.setString(5, attributes);
        cs.setString(6, ip);
        cs.setBoolean(7, islocked);
        cs.setInt(8, attemptsCount);
        cs.setLong(9, timeToUnlock);
        cs.registerOutParameter(10, Types.VARCHAR);

        cs.execute();

        return new PostProcessResult(cs.getInt(1) == 0, "Stored procedure message begin: " + cs.getString(10) + " Stored procedure message end.");

    }

    private boolean checkPasswordHash(String pwdComplex, String password) throws UnsupportedEncodingException, EAuthServerLogic {

        if (PBKDF2.equalsIgnoreCase(pwdComplex.substring(0, PBKDF2.length()))) {
            String[] pwdParts = pwdComplex.split(PBKDF2_PASSWORD_DIVIDER);

            String alg = pwdParts[0];
            String salt = pwdParts[1];
            String hash = pwdParts[2];

            String[] algParts = alg.split(PBKDF2_ALG_DIVIDER);

            int iterations = Integer.parseInt(algParts[2]);

            return hash.equals(getHashForPBKDF2(password, salt, iterations));

        } else {
            String alg;
            String salt;
            String hash;

            String[] pwdParts = pwdComplex.split(PASSWORD_DIVIDER);
            if (pwdParts.length >= 3) {
                alg = getHashAlgorithm2(pwdParts[0]);
                salt = pwdParts[1];
                hash = pwdParts[2];
            } else {
                alg = "SHA-1";
                salt = "";
                hash = pwdComplex;
            }

            return hash.equals(getHash(password + salt + localSecuritySalt, alg));
        }

    }

    private String getSelectFields() {
        String[] fields = searchReturningAttributes.values().toArray(new String[0]);

        String s = null;
        for (String field : fields) {
            field = String.format("\"%s\"", field);
            if (s == null) {
                s = field;
            } else {
                if (s.contains(field)) {
                    continue;
                }
                s = s + ", " + field;
            }
        }

        if (fieldBlocked != null) {
            String field = String.format("\"%s\"", fieldBlocked);
            if (s == null) {
                s = field;
            } else {
                s = s + ", " + field;
            }
        }

        return s;
    }

    @Override
    void getUserInfoByName(ProviderContextHolder context, String name, PrintWriter pw) throws EAuthServerLogic {

        if (getLogger() != null) {
            getLogger().info("Url='" + getConnectionUrl() + "'");
            getLogger().info("name='" + name + "'");
        }

        checkForPossibleSQLInjection(name, USER + name + "' не найден");

        String sql = "";
        try {
            ((SQLLink) context).conn = getConnection();

            sql = String.format("SELECT %s FROM \"%s\" WHERE lower(\"%s\") = ?", getSelectFields(), table, fieldLogin);
            PreparedStatement stat = ((SQLLink) context).conn.prepareStatement(sql);
            stat.setString(1, name.toLowerCase());

            boolean hasResult = stat.execute();
            if (hasResult) {
                ResultSet rs = stat.getResultSet();
                String[] attrs = searchReturningAttributes.keySet().toArray(new String[0]);
                XMLStreamWriter xw = XMLOutputFactory.newInstance().createXMLStreamWriter(pw);
                if (rs.next()) {
                    xw.writeStartDocument("utf-8", "1.0");
                    xw.writeEmptyElement("user");
                    for (String attr : attrs) {
                        writeXMLAttr(xw, attr, rs.getString(searchReturningAttributes.get(attr)));
                    }
                    xw.writeEndDocument();
                    xw.flush();

                    if (getLogger() != null) {
                        getLogger().info(USER + name + "' найден");
                    }

                    return;
                }
            }

            if (getLogger() != null) {
                getLogger().info(USER + name + "' не найден");
            }

        } catch (Exception e) {
            if (getLogger() != null) {
                getLogger().error(String.format(ERROR_SQL_SERVER, getConnectionUrl(), e.getMessage(), sql));
            }
            throw EAuthServerLogic.create(e);
        }
    }

    @Override
    void importUsers(ProviderContextHolder context, PrintWriter pw, boolean needStartDocument) throws EAuthServerLogic {

        if (getLogger() != null) {
            getLogger().info("Url='" + getConnectionUrl() + "'");
        }

        String sql = "";
        try {
            if (((SQLLink) context).conn == null) {
                ((SQLLink) context).conn = getConnection();
            }

            sql = String.format("SELECT %s FROM \"%s\" ORDER BY \"%s\"", getSelectFields(), table, fieldLogin);

            PreparedStatement stat = ((SQLLink) context).conn.prepareStatement(sql);

            boolean hasResult = stat.execute();
            if (hasResult) {
                ResultSet rs = stat.getResultSet();
                String[] attrs = searchReturningAttributes.keySet().toArray(new String[0]);
                XMLStreamWriter xw = XMLOutputFactory.newInstance().createXMLStreamWriter(pw);
                if (needStartDocument) {
                    xw.writeStartDocument("utf-8", "1.0");
                }
                xw.writeStartElement("users");
                writeXMLAttr(xw, "pid", getId());
                while (rs.next()) {
                    xw.writeEmptyElement("user");
                    for (String attr : attrs) {
                        writeXMLAttr(xw, attr, rs.getString(searchReturningAttributes.get(attr)));
                    }
                }
                xw.writeEndDocument();
                xw.flush();
                if (getLogger() != null) {
                    getLogger().info("Импорт пользователей успешно завершен");
                }
            }
        } catch (Exception e) {
            if (getLogger() != null) {
                getLogger().error(String.format(ERROR_SQL_SERVER, getConnectionUrl(), e.getMessage(), sql));
            }
            throw EAuthServerLogic.create(e);
        }
    }

    @Override
    void changePwd(ProviderContextHolder context, String userName, String newpwd) throws EAuthServerLogic {

        if (getLogger() != null) {
            getLogger().info("Url='" + getConnectionUrl() + "'");
            getLogger().info("name='" + userName + "'");
        }

        checkForPossibleSQLInjection(userName, USER + userName + "' не найден");

        String sql = "";
        try {
            ((SQLLink) context).conn = getConnection();

            sql = String.format("UPDATE \"%s\" SET \"%s\" = ? WHERE \"%s\" = ?", table, fieldPassword, fieldLogin);

            PreparedStatement stat = ((SQLLink) context).conn.prepareStatement(sql);


            SecureRandom r = new SecureRandom();
            String salt = String.format("%016x", r.nextLong()) + String.format("%016x", r.nextLong());

            String password = getHashAlgorithm1(hashAlgorithm) + PASSWORD_DIVIDER + salt + PASSWORD_DIVIDER + getHash(newpwd + salt + localSecuritySalt, hashAlgorithm);


            stat.setString(1, password);
            stat.setString(2, userName);

            stat.execute();

        } catch (Exception e) {
            if (getLogger() != null) {
                getLogger().error(String.format(ERROR_SQL_SERVER, getConnectionUrl(), e.getMessage(), sql));
            }
            throw EAuthServerLogic.create(e);
        }

    }

    @Override
    ProviderContextHolder newContextHolder() {
        return new SQLLink();
    }

    /**
     * Возвращает значение функции SHA-1 для строки символов в виде 16-ричного
     * числа, в точности как реализовано в клиентском JavaScript. Необходимо для
     * контроля логинов и паролей
     *
     * @throws UnsupportedEncodingException
     * @throws EAuthServerLogic
     */
    private String getHash(String input, String alg) throws UnsupportedEncodingException, EAuthServerLogic {

        MessageDigest md = mdPool.get(alg);
        if (md == null) {
            try {
                md = MessageDigest.getInstance(alg);
                if (mdPool.get(alg) == null) {
                    mdPool.put(alg, md);
                }
            } catch (NoSuchAlgorithmException e) {
                if (getLogger() != null) {
                    getLogger().error(e.getMessage());
                }
                throw EAuthServerLogic.create("Алгоритм хеширования " + alg + " не доступен");
            }
        }

        synchronized (md) {
            md.reset();
            md.update(input.getBytes("UTF-8"));
            return asHex(md.digest());
        }

    }

    private String getHashForPBKDF2(String password, String salt, int iterations) throws EAuthServerLogic {
        final int keyLength = 256;
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), iterations, keyLength);
            SecretKey key = skf.generateSecret(spec);
            byte[] hashedBytes = key.getEncoded();
//            String res = "Hex.encodeHexString(hashedBytes)";
            HexFormat commaFormat = HexFormat.of();
            String res = commaFormat.formatHex(hashedBytes);
            return res;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw EAuthServerLogic.create(e);
        }
    }

    private String getHashAlgorithm1(String input) {
        return input.toLowerCase().replace("-", "");
    }

    private String getHashAlgorithm2(String input) {
        return input.toUpperCase().replace("SHA", "SHA-");
    }


    /**
     * Тип метода аутентификации.
     */
    enum AuthMethod {
        CHECK, CONNECT
    }

    /**
     * Тип SQL сервера.
     */
    private enum SQLServerType {
        MSSQL, POSTGRESQL, ORACLE, FIREBIRD
    }

    /**
     * Контекст соединения с базой данных.
     */
    private class SQLLink extends ProviderContextHolder {
        private Connection conn = null;

        @Override
        void closeContext() {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

}
