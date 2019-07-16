package org.apache.ibatis.datasource.zgtest;

import java.sql.*;

/**
 * 数据库访问模板代码
 *
 * @author : zhanggong
 * @version : 1.0.0
 */
public class DatabaseTemplate {

    public static void main(String[] args) throws ClassNotFoundException, SQLException {

        String driver = "";
        String url = "";
        String userName = "";
        String password = "";

        Class.forName(driver);
        Connection connection = DriverManager.getConnection(url, userName, password);

        String sql = "";
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql);
        while (resultSet.next()) {
            System.out.println(resultSet.getString(1));
        }


        String prepareSql = "?";
        String prepareParam = "";
        PreparedStatement preparedStatement = connection.prepareStatement(prepareSql);
        preparedStatement.setString(0, prepareParam);
        resultSet = preparedStatement.executeQuery(prepareSql);
        while (resultSet.next()) {
            System.out.println(resultSet.getString(1));
        }
    }
}
