/*
 * Copyright (c) 2013 Malhar Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.lib.sql;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.annotation.ShipContainingJars;
import com.datatorrent.lib.sql.AbstractSqlStreamOperator.InputSchema.ColumnInfo;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Embedded derby sql input operator.   
 */
@ShipContainingJars(classes = {org.apache.derby.jdbc.EmbeddedDriver.class})
public class DerbySqlStreamOperator extends AbstractSqlStreamOperator
{
  protected transient ArrayList<PreparedStatement> insertStatements = new ArrayList<PreparedStatement>(5);
  protected transient PreparedStatement execStatement;
  protected transient ArrayList<PreparedStatement> deleteStatements = new ArrayList<PreparedStatement>(5);
  protected transient Connection db;

  @Override
  public void setup(OperatorContext context)
  {
    System.setProperty("derby.stream.error.file", "/dev/null");
    try {
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    }
    catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    String connUrl = "jdbc:derby:memory:MALHAR_TEMP;create=true";
    PreparedStatement st;

    try {
      db = DriverManager.getConnection(connUrl);
      // create the temporary tables here
      for (int i = 0; i < inputSchemas.size(); i++) {
        InputSchema inputSchema = inputSchemas.get(i);
        if (inputSchema == null || inputSchema.columnInfoMap.isEmpty()) {
          continue;
        }
        String columnSpec = "";
        String columnNames = "";
        String insertQuestionMarks = "";
        int j = 0;
        for (Map.Entry<String, ColumnInfo> entry: inputSchema.columnInfoMap.entrySet()) {
          if (!columnSpec.isEmpty()) {
            columnSpec += ",";
            columnNames += ",";
            insertQuestionMarks += ",";
          }
          columnSpec += entry.getKey();
          columnSpec += " ";
          columnSpec += entry.getValue().type;
          columnNames += entry.getKey();
          insertQuestionMarks += "?";
          entry.getValue().bindIndex = ++j;
        }
        String createTempTableStmt = "DECLARE GLOBAL TEMPORARY TABLE SESSION." + inputSchema.name + "(" + columnSpec + ") NOT LOGGED";
        st = db.prepareStatement(createTempTableStmt);
        st.execute();
        st.close();

        String insertStmt = "INSERT INTO SESSION." + inputSchema.name + " (" + columnNames + ") VALUES (" + insertQuestionMarks + ")";

        insertStatements.add(i, db.prepareStatement(insertStmt));
        deleteStatements.add(i, db.prepareStatement("DELETE FROM SESSION." + inputSchema.name));
      }
      execStatement = db.prepareStatement(statement);
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void beginWindow(long windowId)
  {
    try {
      db.setAutoCommit(false);
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void processTuple(int tableNum, HashMap<String, Object> tuple)
  {
    InputSchema inputSchema = inputSchemas.get(tableNum);

    PreparedStatement insertStatement = insertStatements.get(tableNum);
    try {
      for (Map.Entry<String, Object> entry: tuple.entrySet()) {
        ColumnInfo t = inputSchema.columnInfoMap.get(entry.getKey());
        if (t != null && t.bindIndex != 0) {
          //System.out.println("Binding: "+entry.getValue().toString()+" to "+t.bindIndex);
          insertStatement.setString(t.bindIndex, entry.getValue().toString());
        }
      }

      insertStatement.executeUpdate();
      insertStatement.clearParameters();
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void endWindow()
  {
    try {
      db.commit();
      if (bindings != null) {
        for (int i = 0; i < bindings.size(); i++) {
          execStatement.setString(i, bindings.get(i).toString());
        }
      }
      ResultSet res = execStatement.executeQuery();
      ResultSetMetaData resmeta = res.getMetaData();
      int columnCount = resmeta.getColumnCount();
      while (res.next()) {
        HashMap<String, Object> resultRow = new HashMap<String, Object>();
        for (int i = 1; i <= columnCount; i++) {
          resultRow.put(resmeta.getColumnName(i), res.getObject(i));
        }
        this.result.emit(resultRow);
      }
      execStatement.clearParameters();

      for (PreparedStatement st: deleteStatements) {
        st.executeUpdate();
        st.clearParameters();
      }
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
    bindings = null;
  }

  @Override
  public void teardown()
  {
    try {
      db.close();
    }
    catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

}
