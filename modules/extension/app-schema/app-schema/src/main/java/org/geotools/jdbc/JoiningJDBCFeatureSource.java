/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2011, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */

package org.geotools.jdbc;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.data.DataAccess;
import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.Transaction;
import org.geotools.data.complex.DataAccessRegistry;
import org.geotools.data.jdbc.FilterToSQLException;
import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.data.joining.JoiningQuery;
import org.geotools.factory.Hints;
import org.geotools.feature.AttributeTypeBuilder;
import org.geotools.feature.NameImpl;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.visitor.PostPreProcessFilterSplittingVisitor;
import org.geotools.filter.visitor.SimplifyingFilterVisitor;
import org.geotools.jdbc.JDBCFeatureReader;
import org.geotools.jdbc.JDBCFeatureSource;
import org.geotools.jdbc.PreparedStatementSQLDialect;
import org.geotools.jdbc.SQLDialect;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;

/**
 * This is where the magic happens.
 * The Joining JDBC Feature Source is a "hacking" class rather than a proper subclass.
 * It provides functionality for executing 'joining queries'. 
 * Because only simple features can be returned by the existing geotools database logic, 
 * these joins on the db are not used for linking the actual features together, 
 * but only for putting the simple features in a certain order;
 *  an order that will allow us to do the actual feature chaining faster when we are building
 *  the complex features, because the right features will already be lined up in the right order.
 * 
 * @author Niels Charlier (Curtin University of Technology)
 *
 *
 * @source $URL$
 */
public class JoiningJDBCFeatureSource extends JDBCFeatureSource {
 
    private static final Logger LOGGER = Logging.getLogger("org.geotools.jdbc.JoiningJDBCFeatureSource");
    
    private static final String TEMP_FILTER_ALIAS = "temp_alias_used_for_filter"; 
    public static final String FOREIGN_ID = "FOREIGN_ID" ;
    // attribute to indicate primary key column, so it can be retrieved from the feature type
    public static final String PRIMARY_KEY = "PARENT_TABLE_PKEY";
    
    public JoiningJDBCFeatureSource(JDBCFeatureSource featureSource) throws IOException {     
        super(featureSource);        
    }

    public JoiningJDBCFeatureSource(JDBCFeatureStore featureStore) throws IOException {     
        super(featureStore.delegate);
    }

    /**
     * Field Encoder for converting Filters/Expressions to SQL, will encode table name with field 
     *
     */
    protected class JoiningFieldEncoder implements FilterToSQL.FieldEncoder {
        
        private String tableName;
        
        public JoiningFieldEncoder(String tableName) {
            this.tableName = tableName;
        }
        
        public String encode(String s) {
           StringBuffer buf = new StringBuffer();
           getDataStore().dialect.encodeTableName(tableName, buf);           
           buf.append(".");
           buf.append(s);
           return buf.toString();
        }
    }

    /**
     * Encoding a geometry column with respect to hints
     * Supported Hints are provided by {@link SQLDialect#addSupportedHints(Set)}
     * 
     * @param gatt
     * @param sql
     * @param hints , may be null 
     * @throws SQLException 
     */
    protected void encodeGeometryColumn(GeometryDescriptor gatt, String typeName, StringBuffer sql,Hints hints) throws SQLException {
        
        StringBuffer temp = new StringBuffer();
        getDataStore().encodeGeometryColumn(gatt, temp , hints);
        
        StringBuffer originalColumnName = new StringBuffer();
        getDataStore().dialect.encodeColumnName(gatt.getLocalName(), originalColumnName);
        
        StringBuffer replaceColumnName = new StringBuffer();
        getDataStore().dialect.encodeColumnName(typeName, gatt.getLocalName(), replaceColumnName);

        sql.append(temp.toString().replaceAll(originalColumnName.toString(), replaceColumnName.toString()));  
    }
    
    /**
     * Create order by field for specific table name
     * 
     * @param typeName
     * @param alias
     * @param sort
     * @param orderByFields
     * @throws IOException
     * @throws SQLException
     */
    protected void sort(String typeName, String alias, SortBy[] sort , Set<String> orderByFields, StringBuffer sql) throws IOException, SQLException {    
        for (int i = 0; i < sort.length; i++) {
            if(SortBy.NATURAL_ORDER.equals(sort[i])|| SortBy.REVERSE_ORDER.equals(sort[i])) {
                throw new IOException("Cannot do natural order in joining queries");                    
            } else {                
                StringBuffer mySql = new StringBuffer();
                if (alias != null) {
                   encodeColumnName2(sort[i].getPropertyName().getPropertyName(), alias, mySql, null);
                } else {
                   encodeColumnName(sort[i].getPropertyName().getPropertyName(), typeName, mySql, null);
                }
                if (!mySql.toString().isEmpty() && orderByFields.add(mySql.toString())) {
                    // if it's not already in ORDER BY (because you can't have duplicate column names in order by)
                    // add it to the query buffer
                    if (orderByFields.size() > 1) {
                        sql.append(", ");
                    }
                    sql.append(mySql);

                    if (sort[i].getSortOrder() == SortOrder.DESCENDING) {
                        sql.append(" DESC");
                    } else {
                        sql.append(" ASC");
                    }          
                }
            }
        }
        // GEOT-4554: sort by PK if idExpression is not there
        if (sort.length == 0) {
            PrimaryKey joinKey = null;
            SimpleFeatureType joinFeatureType = getDataStore().getSchema(typeName);
            try {
                joinKey = getDataStore().getPrimaryKey(joinFeatureType);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (PrimaryKeyColumn col : joinKey.getColumns()) {
                StringBuffer mySql = new StringBuffer();
                if (alias != null) {
                    encodeColumnName2(col.getName(), alias, mySql, null);
                } else {
                    encodeColumnName(col.getName(), typeName, mySql, null);
                }
                if (!mySql.toString().isEmpty() && orderByFields.add(mySql.toString())) {
                    // if it's not already in ORDER BY (because you can't have duplicate column names in order by)
                    // add it to the query buffer
                    // this is what's used in AppSchemaDataAccess
                    if (orderByFields.size() > 1) {
                        sql.append(", ");
                    }
                    sql.append(mySql);
                    // this is what's used in AppSchemaDataAccess 
                    sql.append(" ASC");
                }
            }
        }
    }
    
    protected void addMultiValuedSort(String tableName, String myAlias, Set<String> orderByFields,
            JoiningQuery.QueryJoin join, String theirAlias, StringBuffer sql) throws IOException,
            FilterToSQLException, SQLException {        
        
        FilterToSQL toSQL1 = createFilterToSQL(getDataStore().getSchema(tableName));
        toSQL1.setFieldEncoder(new JoiningFieldEncoder(myAlias));
        JDBCDataStore joiningStore = (JDBCDataStore) join.getJoiningSource().getDataStore();
        FilterToSQL toSQL2 = createFilterToSQL(joiningStore, joiningStore.getSchema(join.getJoiningTypeName()));
        toSQL2.setFieldEncoder(new JoiningFieldEncoder(theirAlias));
        String field2 = toSQL2.encodeToString(join.getForeignKeyName());        
        String field1 = toSQL1.encodeToString(join.getJoiningKeyName());

        if (orderByFields.add(field1) && orderByFields.add(field2)) {
            // check that they don't already exists in ORDER BY because duplicate column names aren't allowed
            if (sql.length() > 0) {
                sql.append(", ");     
            }
            sql.append(" CASE WHEN ");
            sql.append(field2);
            sql.append(" = ");
            sql.append(field1);
            sql.append(" THEN 0 ELSE 1 END ASC");
        }
    }
    
    /**
     * Creates ORDER BY for joining query, based on all the sortby's that are specified per
     * joining table
     * 
     * @param query
     * @param sql
     * @throws IOException
     * @throws SQLException
     */
    protected void sort(JoiningQuery query, StringBuffer sql, String[] aliases, Set<String> pkColumnNames, String myAlias) throws IOException, SQLException, FilterToSQLException {
        Set<String> orderByFields = new LinkedHashSet<String>();
        StringBuffer joinOrders = new StringBuffer();
        for (int j = query.getQueryJoins() == null? -1 : query.getQueryJoins().size() -1; j >= -1 ; j-- ) {                
            JoiningQuery.QueryJoin join = j<0 ? null : query.getQueryJoins().get(j);
            SortBy[] sort = j<0? query.getSortBy() : join.getSortBy();
        
            if (sort != null) {
                if (j < 0) {
                    sort(query.getTypeName(), myAlias, sort, orderByFields, joinOrders);
                    
                    if (query.getQueryJoins() != null && query.getQueryJoins().size() > 0) {
                        addMultiValuedSort(query.getTypeName(), myAlias, orderByFields, query.getQueryJoins().get(0), aliases[0], joinOrders);
                    }
                    
                    if (joinOrders.length() > 0) {
                        sql.append(" ORDER BY ");
                        sql.append(joinOrders);                                                         
                    }
                    
                    if (!pkColumnNames.isEmpty()) {
                        for (String pk : pkColumnNames) {
                            
                            StringBuffer pkSql = new StringBuffer();
                            getDataStore().dialect.encodeColumnName(myAlias, pk, pkSql);

                            if (!pkSql.toString().isEmpty() && orderByFields.add(pkSql.toString())) {
                            	if (joinOrders.length() == 0) {
                            	    sql.append(" ORDER BY ");
                            	} else {
                            	    sql.append(", ");                		
                            	}
                                sql.append(pkSql);
                            }
                        }
                    }
                } else {
                    sort(join.getJoiningTypeName(), aliases[j], sort, orderByFields, joinOrders);

                    if (query.getQueryJoins().size() > j + 1) {
                        addMultiValuedSort(join.getJoiningTypeName(), myAlias, orderByFields, query
                                .getQueryJoins().get(j + 1), aliases[j + 1], joinOrders);
                    }
                }
            }
        }
    }
    
    /**
     * Encode column name with table name included.
     * 
     * @param colName
     * @param typeName
     * @param sql
     * @param hints
     * @throws SQLException
     */
    public void encodeColumnName(String colName, String typeName, StringBuffer sql, Hints hints) throws SQLException{
        encodeColumnName(getDataStore(), colName, typeName, sql, hints);
    }

    public void encodeColumnName(JDBCDataStore dataStore, String colName, String typeName, StringBuffer sql, Hints hints) throws SQLException{
        dataStore.encodeTableName(typeName, sql, hints);
        sql.append(".");
        dataStore.dialect.encodeColumnName(colName, sql);

    }
    
    /**
     * Encode column name with table name included, but do not include schema name (for aliases)
     * 
     * @param colName
     * @param typeName
     * @param sql
     * @param hints
     * @throws SQLException
     */
    public void encodeColumnName2(String colName, String typeName, StringBuffer sql, Hints hints) throws SQLException{
        
        getDataStore().dialect.encodeTableName(typeName, sql);                
        sql.append(".");
        getDataStore().dialect.encodeColumnName(colName, sql);
        
    }

    /**
     * Craete the filter to sql converter
     *
     * @param ft
     * @return
     */
    protected FilterToSQL createFilterToSQL(SimpleFeatureType ft) {
        return createFilterToSQL(getDataStore(), ft);
    }

    /**
     * Craete the filter to sql converter
     * 
     *
     * @param dataStore
     * @param ft
     * @return
     */
    protected FilterToSQL createFilterToSQL(JDBCDataStore dataStore, SimpleFeatureType ft) {
        if (  dataStore.getSQLDialect() instanceof PreparedStatementSQLDialect ) {
            return dataStore.createPreparedFilterToSQL(ft);
        } else {
            return dataStore.createFilterToSQL(ft);
        }
        
    }
    
    protected static String createAlias(Set<String> tableNames){
        String alias;
        int index =0;
        do {
            alias = "t" + ++index;
        } while (tableNames.contains(alias));
        return alias;
    }
    
    
    /**
     * Generates a 'SELECT p1, p2, ... FROM ... WHERE ...' prepared statement.
     * 
     * @param featureType
     *            the feature type that the query must return (may contain less attributes than the
     *            native one)
     * @param attributes
     *            the properties queried, or {@link Query#ALL_NAMES} to gather all of them
     * @param query
     *            the query to be run. The type name and property will be ignored, as they are
     *            supposed to have been already embedded into the provided feature type
     * @param cx
     *            The database connection to be used to create the prepared statement
     * @throws SQLException 
     * @throws IOException 
     * @throws FilterToSQLException
     */
    protected String selectSQL(SimpleFeatureType featureType, JoiningQuery query, AtomicReference<PreparedFilterToSQL> toSQLref) throws IOException, SQLException, FilterToSQLException {
        
        // first we create from clause, for aliases
        
        StringBuffer fromclause = new StringBuffer();

        //joining
        Set<String> tableNames = new HashSet<String>();
        
        JDBCFeatureSource lastSource = this;

        String[] aliases = null;

        String myAlias = createAlias(tableNames);
        String curTypeName = myAlias;
        getDataStore().encodeTableName(featureType.getTypeName(), fromclause, query.getHints());
        fromclause.append(' ');
        getDataStore().dialect.encodeTableName(curTypeName, fromclause);

        if (query.getQueryJoins() != null) {
            
            aliases = new String[query.getQueryJoins().size()];

            String lastAlias = curTypeName;
            
            for (int i=0; i< query.getQueryJoins().size(); i++) {
                JoiningQuery.QueryJoin join = query.getQueryJoins().get(i);

                fromclause.append(" INNER JOIN ");

                JDBCFeatureSource currentSource = ((JDBCFeatureStore) join.getJoiningSource()).getFeatureSource();
                FilterToSQL toSQL1 = createFilterToSQL(lastSource.getDataStore(), lastSource.getSchema());
                FilterToSQL toSQL2 = createFilterToSQL(currentSource.getDataStore(), currentSource.getSchema());

                tableNames.add(curTypeName);
                String alias = createAlias(tableNames);

                aliases[i] = alias;

                currentSource.getDataStore().encodeTableName(join.getJoiningTypeName(), fromclause, query.getHints());
                fromclause.append(" ");
                currentSource.getDataStore().dialect.encodeTableName(alias, fromclause);
                fromclause.append(" ON ( ");

                toSQL2.setFieldEncoder(new JoiningFieldEncoder(alias));
                fromclause.append(toSQL2.encodeToString(join.getForeignKeyName()));

                fromclause.append(" = ");
                toSQL1.setFieldEncoder(new JoiningFieldEncoder(lastAlias));
                fromclause.append(toSQL1.encodeToString(join.getJoiningKeyName()));
                fromclause.append(") ");
                lastSource = currentSource;
                lastAlias = alias;
            }
        }
        
        //begin sql
        StringBuffer sql = new StringBuffer();
        sql.append("SELECT ");
        
        // primary key
        PrimaryKey key = null;

        try {
            key = getDataStore().getPrimaryKey(featureType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Set<String> pkColumnNames = new HashSet<String>();
        String colName;
        for ( PrimaryKeyColumn col : key.getColumns() ) {
            colName = col.getName();
            getDataStore().dialect.encodeColumnName(myAlias, colName, sql);
            sql.append(",");
            pkColumnNames.add(colName);
        }
        Set<String> lastPkColumnNames = pkColumnNames;
        
        //other columns
        for (AttributeDescriptor att : featureType.getAttributeDescriptors()) {
            // skip the eventually exposed pk column values
            String columnName = att.getLocalName();
            if(pkColumnNames.contains(columnName))
                continue;
            
            if (att instanceof GeometryDescriptor) {
                //encode as geometry
                encodeGeometryColumn((GeometryDescriptor) att, myAlias, sql, query.getHints());

                //alias it to be the name of the original geometry
                getDataStore().dialect.encodeColumnAlias(columnName, sql);
            } else {

                getDataStore().dialect.encodeColumnName(myAlias, columnName, sql);

            }

            sql.append(",");
        }
        
        if (query.getQueryJoins() != null && query.getQueryJoins().size() > 0) {
            for (int i = 0; i < query.getQueryJoins().size(); i++) {
                List<String> ids = query.getQueryJoins().get(i).getIds();
                for (int j = 0; j < ids.size(); j++) {
                    if (aliases[i] != null) {
                        getDataStore().dialect.encodeColumnName(aliases[i], query.getQueryJoins()
                                .get(i).getIds().get(j), sql);
                    } else {
                        encodeColumnName(query.getQueryJoins().get(i).getIds().get(j), query.getQueryJoins().get(i)
                                .getJoiningTypeName(), sql, query.getHints());
                        
                    }
                    sql.append(" ").append(FOREIGN_ID + "_" + i + "_" + j).append(",");                    
                }
                // GEOT-4554: handle PK as default idExpression
                if (ids.isEmpty()) {
                    PrimaryKey joinKey = null;
                    String joinTypeName = query.getQueryJoins().get(i).getJoiningTypeName();
                    SimpleFeatureType joinFeatureType = getDataStore().getSchema(joinTypeName);

                    try {
                        joinKey = getDataStore().getPrimaryKey(joinFeatureType);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (!joinKey.getColumns().isEmpty()) {
                        lastPkColumnNames.clear();
                    }
                    int j = 0;
                    for (PrimaryKeyColumn col : joinKey.getColumns()) {
                        if (aliases[i] != null) {
                            getDataStore().dialect.encodeColumnName(aliases[i], col.getName(), sql);
                        } else {
                            encodeColumnName((JDBCDataStore) query.getQueryJoins().get(i).getJoiningSource().getDataStore(), col.getName(), joinTypeName, sql, query.getHints());
                        }
                        query.getQueryJoins().get(i).addId(col.getName());
                        sql.append(" ").append(FOREIGN_ID + "_" + i + "_" + j).append(",");
                        j++;
                        lastPkColumnNames.add(col.getName());
                    }
                }
            }
        }
        if (!query.hasIdColumn() && !pkColumnNames.isEmpty()) {
            int pkIndex = 0;
            for (String pk : pkColumnNames) {
                getDataStore().dialect.encodeColumnName(myAlias, pk, sql);
                sql.append(" ").append(PRIMARY_KEY).append("_").append(pkIndex).append(",");
                pkIndex++;
            }
        }
        
        sql.setLength(sql.length() - 1);        

        sql.append(" FROM ");
        
        sql.append(fromclause);
        
        //filtering
        FilterToSQL toSQL = null;
        Filter filter = query.getFilter();
        if (filter != null && !Filter.INCLUDE.equals(filter)) {
            //encode filter
            try {
                SortBy[] lastSortBy = null;
                // leave it as null if it's asking for a subset, since we don't want to join to get
                // other rows of same id
                // since we don't want a full feature, but a subset only
                if (!query.isSubset()) {
                    // grab the full feature type, as we might be encoding a filter
                    // that uses attributes that aren't returned in the results
                    lastSortBy = query.getQueryJoins() == null || query.getQueryJoins().size() == 0 ? query
                            .getSortBy() : query.getQueryJoins()
                            .get(query.getQueryJoins().size() - 1).getSortBy();
                }
                JoiningQuery.QueryJoin lastJoin = query.getQueryJoins() == null || query.getQueryJoins().isEmpty()
                        ? null
                        : query.getQueryJoins().get(query.getQueryJoins().size()-1);
                String lastTableName = lastJoin == null ? query.getTypeName() : lastJoin.getJoiningTypeName();
                JDBCDataStore lastDataStore = (JDBCDataStore) (lastJoin == null
                        ? getDataStore()
                        : lastJoin.getJoiningSource().getDataStore());
                String lastTableAlias = lastJoin == null
                        ? myAlias
                        : aliases[query.getQueryJoins().size()-1];
                
                toSQL = createFilterToSQL(lastDataStore, lastDataStore.getSchema(lastTableName));
                
                if (lastSortBy != null 
                        && (lastSortBy.length > 0 || !lastPkColumnNames.isEmpty())) {
                    //we will use another join for the filter
                    //assuming that the last sort by specifies the ID of the parent feature                   
                    //this way we will ensure that if the table is denormalized, that all rows
                    //with the same ID are included (for multi-valued features)
                    
                    sql.append(" INNER JOIN ( SELECT DISTINCT ");
                    for (int i=0; i < lastSortBy.length; i++) {
                         lastDataStore.dialect.encodeColumnName(null, lastSortBy[i].getPropertyName().getPropertyName(), sql);
                         if (i < lastSortBy.length-1) sql.append(",");
                    }
                    if (lastSortBy.length == 0) {
                        // GEOT-4554: if ID expression is not specified, use PK
                        int i = 0;
                        for (String pk : lastPkColumnNames) {
                            lastDataStore.dialect.encodeColumnName(null, pk, sql);
                            if (i < lastPkColumnNames.size() - 1)
                                sql.append(",");
                            i++;
                        }
                    }
                    sql.append(" FROM ");
                    lastDataStore.encodeTableName(lastTableName, sql, query.getHints());
                    sql.append(" ").append(toSQL.encodeToString(filter));
                    sql.append(" ) ");
                    lastDataStore.dialect.encodeTableName(TEMP_FILTER_ALIAS, sql);
                    sql.append(" ON ( ");
                    for (int i=0; i < lastSortBy.length; i++) {
                        encodeColumnName2(lastSortBy[i].getPropertyName().getPropertyName(), lastTableAlias , sql, null);            
                        sql.append(" = ");
                        encodeColumnName2(lastSortBy[i].getPropertyName().getPropertyName(), TEMP_FILTER_ALIAS , sql, null);
                        if (i < lastSortBy.length-1) sql.append(" AND ");
                    }
                    if (lastSortBy.length == 0) {
                        // GEOT-4554: if ID expression is not specified, use PK
                        int i = 0;
                        for (String pk : lastPkColumnNames) {
                            encodeColumnName2(pk, lastTableAlias, sql, null);
                            sql.append(" = ");
                            encodeColumnName2(pk, TEMP_FILTER_ALIAS, sql, null);
                            if (i < lastPkColumnNames.size() - 1)
                                sql.append(" AND ");
                            i++;
                        }
                    }
                    sql.append(" ) ");                    
                }
                else {
                    toSQL.setFieldEncoder(new JoiningFieldEncoder(curTypeName));                    
                    sql.append(" ").append(toSQL.encodeToString(filter));
                }
            } catch (FilterToSQLException e) {
                throw new RuntimeException(e);
            }
        }

        //sorting
        sort(query, sql, aliases, pkColumnNames, myAlias);
        
        // finally encode limit/offset, if necessary
        getDataStore().applyLimitOffset(sql, query);
        
        if (toSQLref != null && toSQL instanceof PreparedFilterToSQL) {
            toSQLref.set((PreparedFilterToSQL) toSQL);
        }
        
        return sql.toString();
    }

    /**
     * Generates a 'SELECT p1, p2, ... FROM ... WHERE ...' prepared statement.
     * 
     * @param featureType
     *            the feature type that the query must return (may contain less
     *            attributes than the native one)
     * @param attributes
     *            the properties queried, or {@link Query#ALL_NAMES} to gather
     *            all of them
     * @param query
     *            the query to be run. The type name and property will be ignored, as they are
     *            supposed to have been already embedded into the provided feature type
     * @param cx
     *            The database connection to be used to create the prepared
     *            statement
     * @throws FilterToSQLException 
     */
    protected PreparedStatement selectSQLPS( SimpleFeatureType featureType, JoiningQuery query, Connection cx )
        throws SQLException, IOException, FilterToSQLException {
        
        AtomicReference<PreparedFilterToSQL> toSQLref = new AtomicReference<PreparedFilterToSQL>();
        String sql = selectSQL(featureType, query, toSQLref);

        LOGGER.fine( sql );
        PreparedStatement ps = cx.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ps.setFetchSize(getDataStore().fetchSize);
        
        if ( toSQLref.get() != null ) {
            getDataStore().setPreparedFilterValues( ps, toSQLref.get(), 0, cx );
        } 
        
        return ps;
    }
    
    Filter[] splitFilter(Filter original) {
        Filter[] split = new Filter[2];
        if ( original != null ) {
            //create a filter splitter
            PostPreProcessFilterSplittingVisitor splitter = new PostPreProcessFilterSplittingVisitor(getDataStore()
                    .getFilterCapabilities(), null, null);
            original.accept(splitter, null);
        
            split[0] = splitter.getFilterPre();
            split[1] = splitter.getFilterPost();
        }
        
        SimplifyingFilterVisitor visitor = new SimplifyingFilterVisitor();
        visitor.setFIDValidator( new PrimaryKeyFIDValidator( this ) );
        split[0] = (Filter) split[0].accept(visitor, null);
        split[1] = (Filter) split[1].accept(visitor, null);
        
        return split;
    }
    
    protected SimpleFeatureType getFeatureType(SimpleFeatureType origType, JoiningQuery query) throws IOException {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.init(origType);
        
        AttributeTypeBuilder ab = new AttributeTypeBuilder();
        if (query.getQueryJoins() != null) {
            for (int i = 0; i < query.getQueryJoins().size(); i++) {
                if (query.getQueryJoins().get(i).getIds().isEmpty()) {
                    // GEOT-4554: handle PK as default idExpression
                    PrimaryKey joinKey = null;
                    String joinTypeName = query.getQueryJoins().get(i).getJoiningTypeName();
                    SimpleFeatureType joinFeatureType = getDataStore().getSchema(joinTypeName);

                    try {
                        joinKey = getDataStore().getPrimaryKey(joinFeatureType);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    int j = 0;
                    for (PrimaryKeyColumn col : joinKey.getColumns()) {
                        query.getQueryJoins().get(i).addId(col.getName());
                        ab.setBinding(String.class);
                        builder.add(ab.buildDescriptor(new NameImpl(FOREIGN_ID) + "_" + i + "_" + j,
                                ab.buildType()));
                        j++;
                    }
                } else {
                    for (int j = 0; j < query.getQueryJoins().get(i).getIds().size(); j++) {
                        ab.setBinding(String.class);
                        builder.add(ab.buildDescriptor(new NameImpl(FOREIGN_ID) + "_" + i + "_" + 0,
                                ab.buildType()));
                    }
                }
            }
        }
        if (!query.hasIdColumn()) {
            // add primary key for the case where idExpression is not specified
            PrimaryKey key = null;

            try {
                key = getDataStore().getPrimaryKey(origType);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (int j = 0; j < key.getColumns().size(); j++) {
                ab.setBinding(String.class);
                builder.add(ab.buildDescriptor(PRIMARY_KEY + "_" + j,
                        ab.buildType()));
            }
        }
        
        return builder.buildFeatureType();
    }

    protected  FeatureReader<SimpleFeatureType, SimpleFeature> getJoiningReaderInternal(JoiningQuery query) throws IOException {
        // split the filter
        Filter[] split = splitFilter(query.getFilter());
        Filter preFilter = split[0];
        Filter postFilter = split[1];
        
        if (postFilter != null && postFilter != Filter.INCLUDE) {
            throw new IllegalArgumentException ("Postfilters not allowed in Joining Queries");
        }
        
        // rebuild a new query with the same params, but just the pre-filter
        JoiningQuery preQuery = new JoiningQuery(query);
        preQuery.setFilter(preFilter);
        
        // Build the feature type returned by this query. Also build an eventual extra feature type
        // containing the attributes we might need in order to evaluate the post filter
        SimpleFeatureType querySchema;
        if(query.getPropertyNames() == Query.ALL_NAMES) {
            querySchema = getSchema();
        } else {
            querySchema = SimpleFeatureTypeBuilder.retype(getSchema(), query.getPropertyNames());            
        }
        // rebuild and add primary key column if there's no idExpression pointing to a database column
        // this is so we can retrieve the PK later to use for feature chaining grouping
        SimpleFeatureType fullSchema = (query.hasIdColumn() && query.getQueryJoins() == null) ? querySchema
                : getFeatureType(querySchema, query);
        
        //grab connection
        Connection cx = getDataStore().getConnection(getState());
        
        //create the reader
        FeatureReader<SimpleFeatureType, SimpleFeature> reader;
        
        try {
            // this allows PostGIS to page the results and respect the fetch size
            if(getState().getTransaction() == Transaction.AUTO_COMMIT) {
                cx.setAutoCommit(false);
            }
            
            SQLDialect dialect = getDataStore().getSQLDialect();
            if ( dialect instanceof PreparedStatementSQLDialect ) {
                PreparedStatement ps = selectSQLPS(querySchema, preQuery, cx);
                reader = new JDBCFeatureReader( ps, cx, this, fullSchema, query.getHints() );
            } else {
                //build up a statement for the content
                String sql = selectSQL(querySchema, preQuery, null);
                getDataStore().getLogger().fine(sql);
    
                reader = new JDBCFeatureReader( sql, cx, this, fullSchema, query.getHints() );
            }
        } catch (Exception e) {

            try {
                getState().getTransaction().rollback();
            } catch(IOException e2) {
                getDataStore().getLogger().log(Level.WARNING, "Couldn't rollback JDBC transaction.", e2);
            }

            // close the connection
            getDataStore().closeSafe(cx);

            // safely rethrow
            throw (IOException) new IOException().initCause(e);
        }
        
        return reader;
    }
    
    protected  FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query) throws IOException {
        if (query instanceof JoiningQuery) {
            return getJoiningReaderInternal((JoiningQuery) query);
        }
        else {
            return super.getReaderInternal(query);
        }
    }
    
    protected Query resolvePropertyNames( Query query ) {
        /*if (query instanceof JoiningQuery) {
            JoiningQuery jQuery = new JoiningQuery (super.resolvePropertyNames(query));
            jQuery.setJoins(((JoiningQuery)query).getQueryJoins());            
            return jQuery;
        } else {
            return super.resolvePropertyNames(query);
        }*/
        return query;
        
    }
    
    protected Query joinQuery( Query query ) {
        if (this.query==null) {
            return query;
        }
        else if (query instanceof JoiningQuery) {            
            JoiningQuery jQuery = new JoiningQuery(super.joinQuery(query),
                    ((JoiningQuery) query).hasIdColumn());   
            jQuery.setQueryJoins(((JoiningQuery)query).getQueryJoins());            
            return jQuery;            
        }
        else {            
            return super.joinQuery(query);
        }
    }

}
