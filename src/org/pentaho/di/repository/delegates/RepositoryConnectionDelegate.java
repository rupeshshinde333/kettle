package org.pentaho.di.repository.delegates;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.Counter;
import org.pentaho.di.core.Counters;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryObject;

public class RepositoryConnectionDelegate extends BaseRepositoryDelegate {
	private static Class<?> PKG = Repository.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

    public static final int REQUIRED_MAJOR_VERSION = 3;
    public static final int REQUIRED_MINOR_VERSION = 3;

	protected final static int[] KEY_POSITIONS = new int[] {0, 1, 2};

	protected Database			database;
	protected DatabaseMeta        databaseMeta;
	
	protected int					majorVersion;
	protected int					minorVersion;
	
	protected PreparedStatement	psStepAttributesLookup;
	protected PreparedStatement	psStepAttributesInsert;
    protected PreparedStatement   psTransAttributesLookup;
    protected PreparedStatement   psTransAttributesInsert;
    
    protected PreparedStatement   psJobAttributesLookup;
    protected PreparedStatement   psJobAttributesInsert;
	
	protected List<Object[]>           stepAttributesBuffer;
	protected RowMetaInterface         stepAttributesRowMeta;
	
	protected PreparedStatement	pstmt_entry_attributes;

	protected boolean             useBatchProcessing;
	
    private class StepAttributeComparator implements Comparator<Object[]> {

    	public int compare(Object[] r1, Object[] r2) 
    	{
    		try {
    			return stepAttributesRowMeta.compare(r1, r2, KEY_POSITIONS);
    		} catch (KettleValueException e) {
    			return 0; // conversion errors
    		}
    	}
    }
	
	
	public RepositoryConnectionDelegate(Repository repository, DatabaseMeta databaseMeta) {
		super(repository);

		this.databaseMeta = databaseMeta;
		this.database = new Database(databaseMeta);
		
		useBatchProcessing = true; // defaults to true;
        
		psStepAttributesLookup = null;
		psStepAttributesInsert = null;
        psTransAttributesLookup = null;
		pstmt_entry_attributes = null;

		this.majorVersion = REQUIRED_MAJOR_VERSION;
		this.minorVersion = REQUIRED_MINOR_VERSION;
	}

	/**
	 * Connect to the repository 
	 * @param locksource
	 * @return true if the connection went well, false if we couldn't connect.
	 */
	public synchronized boolean connect(String locksource) throws KettleException
	{
		return connect(false, true, locksource, false);
	}

    public synchronized boolean connect(boolean no_lookup, boolean readDirectory, String locksource) throws KettleException
    {
        return connect(no_lookup, readDirectory, locksource, false);
    }

	public synchronized boolean connect(boolean no_lookup, boolean readDirectory, String locksource, boolean ignoreVersion) throws KettleException
	{
		if (repository.getRepositoryInfo().isLocked())
		{
			log.logError(toString(), "Repository is locked by class " + locksource);
			return false;
		}
		boolean retval = true;
		try
		{
			database.initializeVariablesFrom(null); 
			database.connect();
            if (!ignoreVersion) {
            	verifyVersion();
            }
			setAutoCommit(false);
			repository.getRepositoryInfo().setLock(true);
			repository.setLocksource( locksource );
			if (!no_lookup)
			{
				try
				{
					repository.connectionDelegate.setLookupStepAttribute();
					repository.connectionDelegate.setLookupTransAttribute();
					repository.connectionDelegate.setLookupJobEntryAttribute();
				}
				catch (KettleException dbe)
				{
					log.logError(toString(), "Error setting lookup prep.statements: " + dbe.getMessage());
				}
			}

			// Load the directory tree.
            if (readDirectory)
            {
    			try
    			{
    				repository.refreshRepositoryDirectoryTree();
    			}
    			catch (KettleException e)
    			{
    				log.logError(toString(), e.toString());
    			}
            }
            else
            {
                repository.setDirectoryTree(new RepositoryDirectory());
            }
		}
		catch (KettleException e)
		{
			retval = false;
			log.logError(toString(), "Error connecting to the repository!" + e.getMessage());
            throw new KettleException(e);
		}

		return retval;
	}

    /**
     * Get the required repository version for this version of Kettle.
     * @return the required repository version for this version of Kettle.
     */
    public static final String getRequiredVersion()
    {
        return REQUIRED_MAJOR_VERSION + "." + REQUIRED_MINOR_VERSION;
    }


	   protected void verifyVersion() throws KettleException
	    {
	        RowMetaAndData lastUpgrade = null;
	    	String versionTable = databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_VERSION);
	        try
	        {
	            lastUpgrade = database.getOneRow("SELECT "+quote(Repository.FIELD_VERSION_MAJOR_VERSION)+", "+quote(Repository.FIELD_VERSION_MINOR_VERSION)+", "+quote(Repository.FIELD_VERSION_UPGRADE_DATE)+" FROM "+versionTable+" ORDER BY "+quote(Repository.FIELD_VERSION_UPGRADE_DATE)+" DESC");
	        }
	        catch(Exception e)
	        {
	        	try
	        	{
		        	// See if the repository exists at all.  For this we verify table R_USER.
		        	//
	            	String userTable = databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_USER);
		        	database.getOneRow("SELECT * FROM "+userTable);
		        	
		        	// Still here?  That means we have a repository...
		        	//
		            // If we can't retrieve the last available upgrade date:
		            // this means the R_VERSION table doesn't exist.
		            // This table was introduced in version 2.3.0
		            //
		            if(log.isBasic()) 
		            {
		            	log.logBasic(toString(), BaseMessages.getString(PKG, "Repository.Error.GettingInfoVersionTable",versionTable));
		            	log.logBasic(toString(), BaseMessages.getString(PKG, "Repository.Error.NewTable"));
		            	log.logBasic(toString(), "Stack trace: "+Const.getStackTracker(e));
		            }
		            majorVersion = 2;
		            minorVersion = 2;
		
		            lastUpgrade = null;
	        	}
	        	catch(Exception ex)
	        	{
	        		throw new KettleException(BaseMessages.getString(PKG, "Repository.NoRepositoryExists.Messages"));
	        	}
	        }

	        if (lastUpgrade != null)
	        {
	            majorVersion = (int)lastUpgrade.getInteger(Repository.FIELD_VERSION_MAJOR_VERSION, -1);
	            minorVersion = (int)lastUpgrade.getInteger(Repository.FIELD_VERSION_MINOR_VERSION, -1);
	        }
	            
	        if (majorVersion < REQUIRED_MAJOR_VERSION || ( majorVersion==REQUIRED_MAJOR_VERSION && minorVersion<REQUIRED_MINOR_VERSION))
	        {
	            throw new KettleException(BaseMessages.getString(PKG, "Repository.UpgradeRequired.Message", getVersion(), getRequiredVersion()));
	        }
	        
	        if (majorVersion==3 && minorVersion==0) {
	        	// The exception: someone upgraded the repository to version 3.0.0
	        	// In that version, one column got named incorrectly.
	        	// Another upgrade to 3.0.1 or later will fix that.
	        	// However, since we don't have point versions in here, we'll have to look at the column in question...
	        	//
	        	String tableName = databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_TRANS_PARTITION_SCHEMA);
	        	String errorColumn = "TRANSFORMATION";
	    		RowMetaInterface tableFields = database.getTableFields(tableName);
	    		if (tableFields.indexOfValue(errorColumn)>=0)
	    		{
	    			throw new KettleException(BaseMessages.getString(PKG, "Repository.FixFor300Required.Message"));
	    		}        	
	        }
	    }

		public synchronized void disconnect()
		{
			try
			{
				repository.connectionDelegate.closeStepAttributeLookupPreparedStatement();
				repository.connectionDelegate.closeTransAttributeLookupPreparedStatement();
				repository.connectionDelegate.closeLookupJobEntryAttribute();
	            
	            if (!database.isAutoCommit()) commit();
	            repository.getRepositoryInfo().setLock(false);			
			}
			catch (KettleException dbe)
			{
				log.logError(toString(), "Error disconnecting from database : " + dbe.getMessage());
			}
			finally
			{
				database.disconnect();
			}
		}

		public synchronized void setAutoCommit(boolean autocommit)
		{
			if (!autocommit)
				database.setCommit(99999999);
			else
				database.setCommit(0);
		}

		public synchronized void commit() throws KettleException
		{
			try
			{
				if (!database.isAutoCommit()) database.commit();
				
				// Also, clear the counters, reducing the risk of collisions!
				//
				Counters.getInstance().clear();
			}
			catch (KettleException dbe)
			{
				throw new KettleException("Unable to commit repository connection", dbe);
			}
		}

		public synchronized void rollback()
		{
			try
			{
				database.rollback();
				
				// Also, clear the counters, reducing the risk of collisions!
				//
				Counters.getInstance().clear();
			}
			catch (KettleException dbe)
			{
				log.logError(toString(), "Error rolling back repository.");
			}
		}

		
		
	/**
	 * @return the database
	 */
	public Database getDatabase() {
		return database;
	}

	/**
	 * @param database the database to set
	 */
	public void setDatabase(Database database) {
		this.database = database;
	}

	/**
	 * @return the databaseMeta
	 */
	public DatabaseMeta getDatabaseMeta() {
		return databaseMeta;
	}

	/**
	 * @param databaseMeta the databaseMeta to set
	 */
	public void setDatabaseMeta(DatabaseMeta databaseMeta) {
		this.databaseMeta = databaseMeta;
	}

	/**
	 * @return the majorVersion
	 */
	public int getMajorVersion() {
		return majorVersion;
	}

	/**
	 * @param majorVersion the majorVersion to set
	 */
	public void setMajorVersion(int majorVersion) {
		this.majorVersion = majorVersion;
	}

	/**
	 * @return the minorVersion
	 */
	public int getMinorVersion() {
		return minorVersion;
	}

	/**
	 * @param minorVersion the minorVersion to set
	 */
	public void setMinorVersion(int minorVersion) {
		this.minorVersion = minorVersion;
	}

	/**
	 * Get the repository version.
	 * @return The repository version as major version + "." + minor version
	 */
	public String getVersion()
	{
		return majorVersion + "." + minorVersion;
	}
	
	public synchronized void fillStepAttributesBuffer(long id_transformation) throws KettleException
	{
	    String sql = "SELECT "+quote(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_CODE)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_NR)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_VALUE_NUM)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR)+" "+
	                 "FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_STEP_ATTRIBUTE) +" "+
	                 "WHERE "+quote(Repository.FIELD_STEP_ATTRIBUTE_ID_TRANSFORMATION)+" = "+id_transformation+" "+
	                 "ORDER BY "+quote(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_CODE)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_NR)
	                 ;
	    
	    stepAttributesBuffer = database.getRows(sql, -1);
	    stepAttributesRowMeta = database.getReturnRowMeta();
        
	    // must use java-based sort to ensure compatibility with binary search
	    // database ordering may or may not be case-insensitive
	    //
        Collections.sort(stepAttributesBuffer, new StepAttributeComparator());  // in case db sort does not match our sort
	}

	/**
     * @return Returns the stepAttributesBuffer.
     */
    public List<Object[]> getStepAttributesBuffer()
    {
        return stepAttributesBuffer;
    }
    
    /**
     * @param stepAttributesBuffer The stepAttributesBuffer to set.
     */
    public void setStepAttributesBuffer(List<Object[]> stepAttributesBuffer)
    {
        this.stepAttributesBuffer = stepAttributesBuffer;
    }
	
	
	private synchronized RowMetaAndData searchStepAttributeInBuffer(long id_step, String code, long nr) throws KettleValueException
	{
	    int index = searchStepAttributeIndexInBuffer(id_step, code, nr);
	    if (index<0) return null;
	    
	    // Get the row
	    //
        Object[] r = stepAttributesBuffer.get(index);
        
	    // and remove it from the list...
        // stepAttributesBuffer.remove(index);
	    
	    return new RowMetaAndData(stepAttributesRowMeta, r);
	}
	
		private synchronized int searchStepAttributeIndexInBuffer(long id_step, String code, long nr) throws KettleValueException
	{
        Object[] key = new Object[] {
        		new Long(id_step), // ID_STEP
        		code, // CODE
        		new Long(nr), // NR
        };
        

        int index = Collections.binarySearch(stepAttributesBuffer, key, new StepAttributeComparator());

        if (index>=stepAttributesBuffer.size() || index<0) return -1;
        
        // 
        // Check this...  If it is not in there, we didn't find it!
        // stepAttributesRowMeta.compare returns 0 when there are conversion issues
        // so the binarySearch could have 'found' a match when there really isn't one
        //
        Object[] look = stepAttributesBuffer.get(index);
        
        if (stepAttributesRowMeta.compare(look, key, KEY_POSITIONS)==0)
        {
            return index;
        }
        
        return -1;
	}

	private synchronized int searchNrStepAttributes(long id_step, String code) throws KettleValueException
	{
	    // Search the index of the first step attribute with the specified code...
		//
	    int idx = searchStepAttributeIndexInBuffer(id_step, code, 0L);
	    if (idx<0) return 0;
	    
	    int nr = 1;
	    int offset = 1;
        
        if (idx+offset>=stepAttributesBuffer.size())
        {
        	// Only 1, the last of the attributes buffer.
        	//
            return 1; 
        }
        Object[] look = (Object[])stepAttributesBuffer.get(idx+offset);
        RowMetaInterface rowMeta = stepAttributesRowMeta;
        
	    long lookID = rowMeta.getInteger(look, 0);
	    String lookCode = rowMeta.getString(look, 1);
	    
	    while (lookID==id_step && code.equalsIgnoreCase( lookCode ) )
	    {
	    	// Find the maximum
	    	//
	        nr = rowMeta.getInteger(look, 2).intValue() + 1; 
	        offset++;
            if (idx+offset<stepAttributesBuffer.size())
            {
                look = (Object[])stepAttributesBuffer.get(idx+offset);
                
                lookID = rowMeta.getInteger(look, 0);
                lookCode = rowMeta.getString(look, 1);
            }
            else
            {
                return nr;
            }
	    }
	    return nr;
	}
	
	public synchronized void setLookupStepAttribute() throws KettleException
	{
		String sql = "SELECT "+quote(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR)+", "+quote(Repository.FIELD_STEP_ATTRIBUTE_VALUE_NUM)+
			" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_STEP_ATTRIBUTE)+
			" WHERE "+quote(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP)+" = ?  AND "+quote(Repository.FIELD_STEP_ATTRIBUTE_CODE)+" = ?  AND "+quote(Repository.FIELD_STEP_ATTRIBUTE_NR)+" = ? ";

		psStepAttributesLookup = database.prepareSQL(sql);
	}
	
    public synchronized void setLookupTransAttribute() throws KettleException
    {
        String sql = "SELECT "+quote(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_STR)+", "+quote(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_NUM)+
        	" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_TRANS_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION)+" = ?  AND "+quote(Repository.FIELD_TRANS_ATTRIBUTE_CODE)+" = ? AND "+Repository.FIELD_TRANS_ATTRIBUTE_NR+" = ? ";

        psTransAttributesLookup = database.prepareSQL(sql);
    }
    
    public synchronized void closeTransAttributeLookupPreparedStatement() throws KettleException
    {
        database.closePreparedStatement(psTransAttributesLookup);
        psTransAttributesLookup = null;
    }

    public synchronized void setLookupJobAttribute() throws KettleException
    {
        String sql = "SELECT "+quote(Repository.FIELD_JOB_ATTRIBUTE_VALUE_STR)+", "+quote(Repository.FIELD_JOB_ATTRIBUTE_VALUE_NUM)+
        	" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOB_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB)+" = ?  AND "+quote(Repository.FIELD_JOB_ATTRIBUTE_CODE)+" = ? AND "+Repository.FIELD_JOB_ATTRIBUTE_NR+" = ? ";

        psJobAttributesLookup = database.prepareSQL(sql);
    }
    
    public synchronized void closeJobAttributeLookupPreparedStatement() throws KettleException
    {
        database.closePreparedStatement(psTransAttributesLookup);
        psJobAttributesLookup = null;
    }    

	public synchronized void closeStepAttributeLookupPreparedStatement() throws KettleException
	{
		database.closePreparedStatement(psStepAttributesLookup);
		psStepAttributesLookup = null;
	}
	
	public synchronized void closeStepAttributeInsertPreparedStatement() throws KettleException
	{
	    if (psStepAttributesInsert!=null)
	    {
		    database.emptyAndCommit(psStepAttributesInsert, useBatchProcessing, 1); // batch mode!
			psStepAttributesInsert = null;
	    }
	}

    public synchronized void closeTransAttributeInsertPreparedStatement() throws KettleException
    {
        if (psTransAttributesInsert!=null)
        {
            database.emptyAndCommit(psTransAttributesInsert, useBatchProcessing, 1); // batch mode!
            psTransAttributesInsert = null;
        }
    }


	private RowMetaAndData getStepAttributeRow(long id_step, int nr, String code) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
		par.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP, ValueMetaInterface.TYPE_INTEGER), new Long(id_step));
		par.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
		par.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));

		database.setValues(par.getRowMeta(), par.getData(), psStepAttributesLookup);

		Object[] rowData =  database.getLookup(psStepAttributesLookup);
        return new RowMetaAndData(database.getReturnRowMeta(), rowData);
	}

    public RowMetaAndData getTransAttributeRow(long id_transformation, int nr, String code) throws KettleException
    {
        RowMetaAndData par = new RowMetaAndData();
        par.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new Long(id_transformation));
        par.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        par.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));

        database.setValues(par, psTransAttributesLookup);
        Object[] r = database.getLookup(psTransAttributesLookup);
        if (r==null) return null;
        return new RowMetaAndData(database.getReturnRowMeta(), r);
    }
    
    public RowMetaAndData getJobAttributeRow(long id_job, int nr, String code) throws KettleException
    {
        RowMetaAndData par = new RowMetaAndData();
        par.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB, ValueMetaInterface.TYPE_INTEGER), new Long(id_job));
        par.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        par.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));

        database.setValues(par, psJobAttributesLookup);
        Object[] r = database.getLookup(psJobAttributesLookup);
        if (r==null) return null;
        return new RowMetaAndData(database.getReturnRowMeta(), r);
    }    

	public synchronized long getStepAttributeInteger(long id_step, int nr, String code) throws KettleException
	{
		RowMetaAndData r = null;
		if (stepAttributesBuffer!=null) r = searchStepAttributeInBuffer(id_step, code, (long)nr);
		else                            r = getStepAttributeRow(id_step, nr, code);
		if (r == null)
			return 0;
		return r.getInteger(Repository.FIELD_STEP_ATTRIBUTE_VALUE_NUM, 0L);
	}

	public synchronized long findStepAttributeID(long id_step, int nr, String code) throws KettleException
	{
		RowMetaAndData r = null;
		if (stepAttributesBuffer!=null) r = searchStepAttributeInBuffer(id_step, code, (long)nr);
		else                            r = getStepAttributeRow(id_step, nr, code);
		if (r == null) return -1L;
		
		return r.getInteger(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP, -1L);
	}
	
	public synchronized String getStepAttributeString(long id_step, int nr, String code) throws KettleException
	{
		RowMetaAndData r = null;
		if (stepAttributesBuffer!=null) r = searchStepAttributeInBuffer(id_step, code, (long)nr);
		else                            r = getStepAttributeRow(id_step, nr, code);
		if (r == null)
			return null;
		return r.getString(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR, null);
	}

	public boolean getStepAttributeBoolean(long id_step, int nr, String code, boolean def) throws KettleException
	{
		RowMetaAndData r = null;
		if (stepAttributesBuffer!=null) r = searchStepAttributeInBuffer(id_step, code, (long)nr);
		else                            r = getStepAttributeRow(id_step, nr, code);
		
		if (r == null) return def;
        String v = r.getString(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR, null);
        if (v==null || Const.isEmpty(v)) return def;
		return ValueMeta.convertStringToBoolean(v).booleanValue();
	}

    public boolean getStepAttributeBoolean(long id_step, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        if (stepAttributesBuffer!=null) r = searchStepAttributeInBuffer(id_step, code, (long)nr);
        else                            r = getStepAttributeRow(id_step, nr, code);
        if (r == null)
            return false;
        return ValueMeta.convertStringToBoolean(r.getString(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR, null)).booleanValue();
    }

	public synchronized long getStepAttributeInteger(long id_step, String code) throws KettleException
	{
		return getStepAttributeInteger(id_step, 0, code);
	}

	public synchronized String getStepAttributeString(long id_step, String code) throws KettleException
	{
		return getStepAttributeString(id_step, 0, code);
	}

	public boolean getStepAttributeBoolean(long id_step, String code) throws KettleException
	{
		return getStepAttributeBoolean(id_step, 0, code);
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, String code, String value) throws KettleException {
		return saveStepAttribute(code, 0, id_transformation, id_step, 0.0, value);
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, String code, double value) throws KettleException {
		return saveStepAttribute(code, 0, id_transformation, id_step, value, null);
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, String code, boolean value) throws KettleException {
		return saveStepAttribute(code, 0, id_transformation, id_step, 0.0, value ? "Y" : "N");
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, long nr, String code, String value) throws KettleException {
		return saveStepAttribute(code, nr, id_transformation, id_step, 0.0, value);
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, long nr, String code, double value) throws KettleException {
		return saveStepAttribute(code, nr, id_transformation, id_step, value, null);
	}

	public synchronized long saveStepAttribute(long id_transformation, long id_step, long nr, String code, boolean value) throws KettleException {
		return saveStepAttribute(code, nr, id_transformation, id_step, 0.0, value ? "Y" : "N");
	}

	private long saveStepAttribute(String code, long nr, long id_transformation, long id_step, double value_num, String value_str) throws KettleException {
		return insertStepAttribute(id_transformation, id_step, nr, code, value_num, value_str);
	}


	public synchronized int countNrStepAttributes(long id_step, String code) throws KettleException
	{
	    if (stepAttributesBuffer!=null) // see if we can do this in memory...
	    {
	        int nr = searchNrStepAttributes(id_step, code);
            return nr;
	    }
	    else
	    {
			String sql = "SELECT COUNT(*) FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_STEP_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP)+" = ? AND "+quote(Repository.FIELD_STEP_ATTRIBUTE_CODE)+" = ?";
			RowMetaAndData table = new RowMetaAndData();
			table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP, ValueMetaInterface.TYPE_INTEGER), new Long(id_step));
			table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
			RowMetaAndData r = database.getOneRow(sql, table.getRowMeta(), table.getData());
			if (r == null || r.getData()==null) return 0;
            return (int) r.getInteger(0, 0L);
	    }
	}
    
    // TRANS ATTRIBUTES: get
    
    public synchronized String getTransAttributeString(long id_transformation, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_transformation, nr, code);
        if (r == null)
            return null;
        return r.getString(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_STR, null);
    }

    public synchronized boolean getTransAttributeBoolean(long id_transformation, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_transformation, nr, code);
        if (r == null)
            return false;
        return r.getBoolean(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_STR, false);
    }

    public synchronized double getTransAttributeNumber(long id_transformation, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_transformation, nr, code);
        if (r == null)
            return 0.0;
        return r.getNumber(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_NUM, 0.0);
    }

    public synchronized long getTransAttributeInteger(long id_transformation, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_transformation, nr, code);
        if (r == null)
            return 0L;
        return r.getInteger(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_NUM, 0L);
    }
    
    public synchronized int countNrTransAttributes(long id_transformation, String code) throws KettleException
    {
        String sql = "SELECT COUNT(*) FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_TRANS_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION)+" = ? AND "+quote(Repository.FIELD_TRANS_ATTRIBUTE_CODE)+" = ?";
        RowMetaAndData table = new RowMetaAndData();
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new Long(id_transformation));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        RowMetaAndData r = database.getOneRow(sql, table.getRowMeta(), table.getData());
        if (r == null|| r.getData()==null)
            return 0;
        
        return (int) r.getInteger(0, 0L);
    }

    public synchronized List<Object[]> getTransAttributes(long id_transformation, String code, long nr) throws KettleException
    {
        String sql = "SELECT *"+
        	" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_TRANS_ATTRIBUTE)+
        	" WHERE "+quote(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION)+" = ? AND "+quote(Repository.FIELD_TRANS_ATTRIBUTE_CODE)+" = ? AND "+quote(Repository.FIELD_TRANS_ATTRIBUTE_NR)+" = ?"+
        	" ORDER BY "+quote(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_NUM);
        
        RowMetaAndData table = new RowMetaAndData();
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new Long(id_transformation));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
        
        return database.getRows(sql, 0);
    }

    // JOB ATTRIBUTES: get
    
    public synchronized String getJobAttributeString(long id_job, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_job, nr, code);
        if (r == null)
            return null;
        return r.getString(Repository.FIELD_JOB_ATTRIBUTE_VALUE_STR, null);
    }

    public synchronized boolean getJobAttributeBoolean(long id_job, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getTransAttributeRow(id_job, nr, code);
        if (r == null)
            return false;
        return r.getBoolean(Repository.FIELD_JOB_ATTRIBUTE_VALUE_STR, false);
    }

    public synchronized double getJobAttributeNumber(long id_job, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getJobAttributeRow(id_job, nr, code);
        if (r == null)
            return 0.0;
        return r.getNumber(Repository.FIELD_JOB_ATTRIBUTE_VALUE_NUM, 0.0);
    }

    public synchronized long getJobAttributeInteger(long id_job, int nr, String code) throws KettleException
    {
        RowMetaAndData r = null;
        r = getJobAttributeRow(id_job, nr, code);
        if (r == null)
            return 0L;
        return r.getInteger(Repository.FIELD_JOB_ATTRIBUTE_VALUE_NUM, 0L);
    }
    
    public synchronized int countNrJobAttributes(long id_job, String code) throws KettleException
    {
        String sql = "SELECT COUNT(*) FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOB_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB)+" = ? AND "+quote(Repository.FIELD_JOB_ATTRIBUTE_CODE)+" = ?";
        RowMetaAndData table = new RowMetaAndData();
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB, ValueMetaInterface.TYPE_INTEGER), new Long(id_job));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        RowMetaAndData r = database.getOneRow(sql, table.getRowMeta(), table.getData());
        if (r == null|| r.getData()==null)
            return 0;
        
        return (int) r.getInteger(0, 0L);
    }

    public synchronized List<Object[]> getJobAttributes(long id_job, String code, long nr) throws KettleException
    {
        String sql = "SELECT *"+
        	" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOB_ATTRIBUTE)+
        	" WHERE "+quote(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB)+" = ? AND "+quote(Repository.FIELD_JOB_ATTRIBUTE_CODE)+" = ? AND "+quote(Repository.FIELD_JOB_ATTRIBUTE_NR)+" = ?"+
        	" ORDER BY "+quote(Repository.FIELD_JOB_ATTRIBUTE_VALUE_NUM);
        
        RowMetaAndData table = new RowMetaAndData();
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB, ValueMetaInterface.TYPE_INTEGER), new Long(id_job));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
        
        return database.getRows(sql, 0);
    }    
    
	// JOBENTRY ATTRIBUTES: SAVE

	// WANTED: throw extra exceptions to locate storage problems (strings too long etc)
	//
	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, String code, String value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, 0, id_job, id_jobentry, 0.0, value);
	}

	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, String code, double value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, 0, id_job, id_jobentry, value, null);
	}

	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, String code, boolean value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, 0, id_job, id_jobentry, 0.0, value ? "Y" : "N");
	}

	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, long nr, String code, String value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, nr, id_job, id_jobentry, 0.0, value);
	}

	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, long nr, String code, double value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, nr, id_job, id_jobentry, value, null);
	}

	public synchronized long saveJobEntryAttribute(long id_job, long id_jobentry, long nr, String code, boolean value)
			throws KettleException
	{
		return saveJobEntryAttribute(code, nr, id_job, id_jobentry, 0.0, value ? "Y" : "N");
	}

	private long saveJobEntryAttribute(String code, long nr, long id_job, long id_jobentry, double value_num,
			String value_str) throws KettleException
	{
		return insertJobEntryAttribute(id_job, id_jobentry, nr, code, value_num, value_str);
	}

	public synchronized long insertJobEntryAttribute(long id_job, long id_jobentry, long nr, String code, double value_num,
			String value_str) throws KettleException
	{
		long id = getNextJobEntryAttributeID();

		RowMetaAndData table = new RowMetaAndData();

		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY_ATTRIBUTE, ValueMetaInterface.TYPE_INTEGER), new Long(id));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOB, ValueMetaInterface.TYPE_INTEGER), new Long(id_job));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY, ValueMetaInterface.TYPE_INTEGER), new Long(id_jobentry));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_NUM, ValueMetaInterface.TYPE_NUMBER), new Double(value_num));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_STR, ValueMetaInterface.TYPE_STRING), value_str);

		database.prepareInsert(table.getRowMeta(), Repository.TABLE_R_JOBENTRY_ATTRIBUTE);
		database.setValuesInsert(table);
		database.insertRow();
		database.closeInsert();

		return id;
	}

	public synchronized long getNextJobEntryAttributeID() throws KettleException
	{
	    return getNextID(databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOBENTRY_ATTRIBUTE), quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY_ATTRIBUTE));
	}
	
	public synchronized long getNextID(String tableName, String fieldName) throws KettleException
	{
	    String counterName = tableName+"."+fieldName;
	    Counter counter = Counters.getInstance().getCounter(counterName);
	    if (counter==null)
	    {
	        long id = getNextTableID(tableName, fieldName);
	        counter = new Counter(id);
	        Counters.getInstance().setCounter(counterName, counter);
	        return counter.next();
	    }
	    else
	    {
	        return counter.next();
	    }
	}


	private synchronized long getNextTableID(String tablename, String idfield) throws KettleException
	{
		long retval = -1;

		RowMetaAndData r = database.getOneRow("SELECT MAX(" + idfield + ") FROM " + tablename);
		if (r != null)
		{
			Long id = r.getInteger(0);
			
			if (id == null)
			{
				if (log.isDebug()) log.logDebug(toString(), "no max(" + idfield + ") found in table " + tablename);
				retval = 1;
			}
			else
			{
                if (log.isDebug()) log.logDebug(toString(), "max(" + idfield + ") found in table " + tablename + " --> " + idfield + " number: " + id);
				retval = id.longValue() + 1L;
			}
		}
		return retval;
	}
	
	// JOBENTRY ATTRIBUTES: GET

	public synchronized void setLookupJobEntryAttribute() throws KettleException
	{
		String sql = "SELECT "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_STR)+", "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_NUM)+
		" FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOBENTRY_ATTRIBUTE)+
		" WHERE "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY)+" = ? AND "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_CODE)+" = ?  AND "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_NR)+" = ? ";

		pstmt_entry_attributes = database.prepareSQL(sql);
	}

	public synchronized void closeLookupJobEntryAttribute() throws KettleException
	{
		database.closePreparedStatement(pstmt_entry_attributes);
        pstmt_entry_attributes = null;
	}

	private RowMetaAndData getJobEntryAttributeRow(long id_jobentry, int nr, String code) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
		par.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY, ValueMetaInterface.TYPE_INTEGER), new Long(id_jobentry));
		par.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
		par.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));

		database.setValues(par.getRowMeta(), par.getData(), pstmt_entry_attributes);
		Object[] rowData = database.getLookup(pstmt_entry_attributes);
        return new RowMetaAndData(database.getReturnRowMeta(), rowData);
	}

	public synchronized long getJobEntryAttributeInteger(long id_jobentry, int nr, String code) throws KettleException
	{
		RowMetaAndData r = getJobEntryAttributeRow(id_jobentry, nr, code);
		if (r == null)
			return 0;
		return r.getInteger(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_NUM, 0L);
	}

	public double getJobEntryAttributeNumber(long id_jobentry, int nr, String code) throws KettleException
	{
		RowMetaAndData r = getJobEntryAttributeRow(id_jobentry, nr, code);
		if (r == null)
			return 0.0;
		return r.getNumber(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_NUM, 0.0);
	}

	public synchronized String getJobEntryAttributeString(long id_jobentry, int nr, String code) throws KettleException
	{
		RowMetaAndData r = getJobEntryAttributeRow(id_jobentry, nr, code);
		if (r == null)
			return null;
		return r.getString(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_STR, null);
	}

	public boolean getJobEntryAttributeBoolean(long id_jobentry, int nr, String code) throws KettleException
	{
		return getJobEntryAttributeBoolean(id_jobentry, nr, code, false);
	}

	public boolean getJobEntryAttributeBoolean(long id_jobentry, int nr, String code, boolean def) throws KettleException
	{
		RowMetaAndData r = getJobEntryAttributeRow(id_jobentry, nr, code);
		if (r == null) return def;
        String v = r.getString(Repository.FIELD_JOBENTRY_ATTRIBUTE_VALUE_STR, null);
        if (v==null || Const.isEmpty(v)) return def;
        return ValueMeta.convertStringToBoolean(v).booleanValue();
	}

	public double getJobEntryAttributeNumber(long id_jobentry, String code) throws KettleException
	{
		return getJobEntryAttributeNumber(id_jobentry, 0, code);
	}

	public synchronized long getJobEntryAttributeInteger(long id_jobentry, String code) throws KettleException
	{
		return getJobEntryAttributeInteger(id_jobentry, 0, code);
	}

	public synchronized String getJobEntryAttributeString(long id_jobentry, String code) throws KettleException
	{
		return getJobEntryAttributeString(id_jobentry, 0, code);
	}

	public boolean getJobEntryAttributeBoolean(long id_jobentry, String code) throws KettleException
	{
		return getJobEntryAttributeBoolean(id_jobentry, 0, code, false);
	}

	public boolean getJobEntryAttributeBoolean(long id_jobentry, String code, boolean def) throws KettleException
	{
		return getJobEntryAttributeBoolean(id_jobentry, 0, code, def);
	}

	public synchronized int countNrJobEntryAttributes(long id_jobentry, String code) throws KettleException
	{
		String sql = "SELECT COUNT(*) FROM "+databaseMeta.getQuotedSchemaTableCombination(null, Repository.TABLE_R_JOBENTRY_ATTRIBUTE)+" WHERE "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY)+" = ? AND "+quote(Repository.FIELD_JOBENTRY_ATTRIBUTE_CODE)+" = ?";
		RowMetaAndData table = new RowMetaAndData();
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_ID_JOBENTRY, ValueMetaInterface.TYPE_INTEGER), new Long(id_jobentry));
		table.addValue(new ValueMeta(Repository.FIELD_JOBENTRY_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
		RowMetaAndData r = database.getOneRow(sql, table.getRowMeta(), table.getData());
		if (r == null || r.getData()==null) return 0;
		return (int) r.getInteger(0, 0L);
	}

	
	/////////////////////////////////////////////////////////////////////////////////////
	// GET NEW IDS
	/////////////////////////////////////////////////////////////////////////////////////

	public synchronized long getNextTransformationID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_TRANSFORMATION), quote(Repository.FIELD_TRANSFORMATION_ID_TRANSFORMATION));
	}

	public synchronized long getNextJobID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_JOB), quote(Repository.FIELD_JOB_ID_JOB));
	}

	public synchronized long getNextNoteID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_NOTE), quote(Repository.FIELD_NOTE_ID_NOTE));
	}
    
    public synchronized long getNextLogID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_REPOSITORY_LOG), quote(Repository.FIELD_REPOSITORY_LOG_ID_REPOSITORY_LOG));
    }

	public synchronized long getNextDatabaseID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_DATABASE), quote(Repository.FIELD_DATABASE_ID_DATABASE));
	}

	public synchronized long getNextDatabaseTypeID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_DATABASE_TYPE), quote(Repository.FIELD_DATABASE_TYPE_ID_DATABASE_TYPE));
	}

	public synchronized long getNextDatabaseConnectionTypeID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_DATABASE_CONTYPE), quote(Repository.FIELD_DATABASE_CONTYPE_ID_DATABASE_CONTYPE));
	}

	public synchronized long getNextLoglevelID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_LOGLEVEL), quote(Repository.FIELD_LOGLEVEL_ID_LOGLEVEL));
	}

	public synchronized long getNextStepTypeID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_STEP_TYPE), quote(Repository.FIELD_STEP_TYPE_ID_STEP_TYPE));
	}

	public synchronized long getNextStepID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_STEP), quote(Repository.FIELD_STEP_ID_STEP));
	}

	public synchronized long getNextJobEntryID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_JOBENTRY), quote(Repository.FIELD_JOBENTRY_ID_JOBENTRY));
	}

	public synchronized long getNextJobEntryTypeID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_JOBENTRY_TYPE), quote(Repository.FIELD_JOBENTRY_TYPE_ID_JOBENTRY_TYPE));
	}

	public synchronized long getNextJobEntryCopyID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_JOBENTRY_COPY), quote(Repository.FIELD_JOBENTRY_COPY_ID_JOBENTRY_COPY));
	}

	public synchronized long getNextStepAttributeID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_STEP_ATTRIBUTE), quote(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP_ATTRIBUTE));
	}
	
    public synchronized long getNextTransAttributeID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_TRANS_ATTRIBUTE), quote(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANS_ATTRIBUTE));
    }

    public synchronized long getNextJobAttributeID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_JOB_ATTRIBUTE), quote(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB_ATTRIBUTE));                                                                                                         
    }    
    
    public synchronized long getNextDatabaseAttributeID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_DATABASE_ATTRIBUTE), quote(Repository.FIELD_DATABASE_ATTRIBUTE_ID_DATABASE_ATTRIBUTE));
    }

	public synchronized long getNextTransHopID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_TRANS_HOP), quote(Repository.FIELD_TRANS_HOP_ID_TRANS_HOP));
	}

	public synchronized long getNextJobHopID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_JOB_HOP), quote(Repository.FIELD_JOB_HOP_ID_JOB_HOP));
	}

	public synchronized long getNextDepencencyID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_DEPENDENCY), quote(Repository.FIELD_DEPENDENCY_ID_DEPENDENCY));
	}
    
    public synchronized long getNextPartitionSchemaID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_PARTITION_SCHEMA), quote(Repository.FIELD_PARTITION_SCHEMA_ID_PARTITION_SCHEMA));
    }

    public synchronized long getNextPartitionID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_PARTITION), quote(Repository.FIELD_PARTITION_ID_PARTITION));
    }

    public synchronized long getNextTransformationPartitionSchemaID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_TRANS_PARTITION_SCHEMA), quote(Repository.FIELD_TRANS_PARTITION_SCHEMA_ID_TRANS_PARTITION_SCHEMA));
    }
    
    public synchronized long getNextClusterID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_CLUSTER), quote(Repository.FIELD_CLUSTER_ID_CLUSTER));
    }

    public synchronized long getNextSlaveServerID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_SLAVE), quote(Repository.FIELD_SLAVE_ID_SLAVE));
    }
    
    public synchronized long getNextClusterSlaveID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_CLUSTER_SLAVE), quote(Repository.FIELD_CLUSTER_SLAVE_ID_CLUSTER_SLAVE));
    }
    
    public synchronized long getNextTransformationSlaveID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_TRANS_SLAVE), quote(Repository.FIELD_TRANS_SLAVE_ID_TRANS_SLAVE));
    }
    
    public synchronized long getNextTransformationClusterID() throws KettleException
    {
        return getNextID(quoteTable(Repository.TABLE_R_TRANS_CLUSTER), quote(Repository.FIELD_TRANS_CLUSTER_ID_TRANS_CLUSTER));
    }
    
	public synchronized long getNextConditionID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_CONDITION), quote(Repository.FIELD_CONDITION_ID_CONDITION));
	}

	public synchronized long getNextValueID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_VALUE), quote(Repository.FIELD_VALUE_ID_VALUE));
	}

	public synchronized long getNextUserID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_USER), quote(Repository.FIELD_USER_ID_USER));
	}

	public synchronized long getNextProfileID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_PROFILE), quote(Repository.FIELD_PROFILE_ID_PROFILE));
	}

	public synchronized long getNextPermissionID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_PERMISSION), quote(Repository.FIELD_PERMISSION_ID_PERMISSION));
	}


    
    public synchronized void clearNextIDCounters()
    {
        Counters.getInstance().clear();
    }

	public synchronized long getNextDirectoryID() throws KettleException
	{
		return getNextID(quoteTable(Repository.TABLE_R_DIRECTORY), quote(Repository.FIELD_DIRECTORY_ID_DIRECTORY));
	}

	public synchronized long insertStepAttribute(long id_transformation, long id_step, long nr, String code, double value_num,
			String value_str) throws KettleException
	{
		long id = getNextStepAttributeID();

		RowMetaAndData table = new RowMetaAndData();

		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP_ATTRIBUTE, ValueMetaInterface.TYPE_INTEGER), new Long(id));
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new Long(id_transformation));
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_ID_STEP, ValueMetaInterface.TYPE_INTEGER), new Long(id_step));
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_VALUE_NUM, ValueMetaInterface.TYPE_NUMBER), new Double(value_num));
		table.addValue(new ValueMeta(Repository.FIELD_STEP_ATTRIBUTE_VALUE_STR, ValueMetaInterface.TYPE_STRING), value_str);

		/* If we have prepared the insert, we don't do it again.
		 * We asume that all the step insert statements come one after the other.
		 */
		
		if (psStepAttributesInsert == null)
		{
		    String sql = database.getInsertStatement(Repository.TABLE_R_STEP_ATTRIBUTE, table.getRowMeta());
		    psStepAttributesInsert = database.prepareSQL(sql);
		}
		database.setValues(table, psStepAttributesInsert);
		database.insertRow(psStepAttributesInsert, useBatchProcessing);
		
        if (log.isDebug()) log.logDebug(toString(), "saved attribute ["+code+"]");
		
		return id;
	}
    
    public synchronized long insertTransAttribute(long id_transformation, long nr, String code, long value_num, String value_str) throws KettleException
    {
        long id = getNextTransAttributeID();

        RowMetaAndData table = new RowMetaAndData();

        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANS_ATTRIBUTE, ValueMetaInterface.TYPE_INTEGER), new Long(id));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_ID_TRANSFORMATION, ValueMetaInterface.TYPE_INTEGER), new Long(id_transformation));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_NUM, ValueMetaInterface.TYPE_INTEGER), new Long(value_num));
        table.addValue(new ValueMeta(Repository.FIELD_TRANS_ATTRIBUTE_VALUE_STR, ValueMetaInterface.TYPE_STRING), value_str);

        /* If we have prepared the insert, we don't do it again.
         * We asume that all the step insert statements come one after the other.
         */
        
        if (psTransAttributesInsert == null)
        {
            String sql = database.getInsertStatement(Repository.TABLE_R_TRANS_ATTRIBUTE, table.getRowMeta());
            psTransAttributesInsert = database.prepareSQL(sql);
        }
        database.setValues(table, psTransAttributesInsert);
        database.insertRow(psTransAttributesInsert, useBatchProcessing);
        
        if (log.isDebug()) log.logDebug(toString(), "saved transformation attribute ["+code+"]");
        
        return id;
    }

    public synchronized long insertJobAttribute(long id_job, long nr, String code, long value_num, String value_str) throws KettleException
    {
    	long id = getNextJobAttributeID();
    	
    	//System.out.println("Insert job attribute : id_job="+id_job+", code="+code+", value_str="+value_str);

        RowMetaAndData table = new RowMetaAndData();

        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB_ATTRIBUTE, ValueMetaInterface.TYPE_INTEGER), new Long(id));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_ID_JOB, ValueMetaInterface.TYPE_INTEGER), new Long(id_job));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_NR, ValueMetaInterface.TYPE_INTEGER), new Long(nr));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_CODE, ValueMetaInterface.TYPE_STRING), code);
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_VALUE_NUM, ValueMetaInterface.TYPE_INTEGER), new Long(value_num));
        table.addValue(new ValueMeta(Repository.FIELD_JOB_ATTRIBUTE_VALUE_STR, ValueMetaInterface.TYPE_STRING), value_str);

        /* If we have prepared the insert, we don't do it again.
         * We asume that all the step insert statements come one after the other.
         */
        
        if (psJobAttributesInsert == null)
        {
            String sql = database.getInsertStatement(Repository.TABLE_R_JOB_ATTRIBUTE, table.getRowMeta());
            psJobAttributesInsert = database.prepareSQL(sql);
        }
        database.setValues(table, psJobAttributesInsert);
        database.insertRow(psJobAttributesInsert, useBatchProcessing);
        
        if (log.isDebug()) log.logDebug(toString(), "saved job attribute ["+code+"]");
        
        return id;
    }    
    
    
    
    
    
	public synchronized void updateTableRow(String tablename, String idfield, RowMetaAndData values, long id) throws KettleException
	{
		String sets[] = new String[values.size()];
		for (int i = 0; i < values.size(); i++)
			sets[i] = values.getValueMeta(i).getName();
		String codes[] = new String[] { idfield };
		String condition[] = new String[] { "=" };

		database.prepareUpdate(tablename, codes, condition, sets);

		values.addValue(new ValueMeta(idfield, ValueMetaInterface.TYPE_INTEGER), new Long(id));

		database.setValuesUpdate(values.getRowMeta(), values.getData());
		database.updateRow();
		database.closeUpdate();
	}

	public synchronized void updateTableRow(String tablename, String idfield, RowMetaAndData values) throws KettleException
	{
		long id = values.getInteger(idfield, 0L);
		values.removeValue(idfield);
		String sets[] = new String[values.size()];
		for (int i = 0; i < values.size(); i++)
			sets[i] = values.getValueMeta(i).getName();
		String codes[] = new String[] { idfield };
		String condition[] = new String[] { "=" };

		database.prepareUpdate(tablename, codes, condition, sets);

		values.addValue(new ValueMeta(idfield, ValueMetaInterface.TYPE_INTEGER), new Long(id));

		database.setValuesUpdate(values.getRowMeta(), values.getData());
		database.updateRow();
	}


	
	
	
	
	
    /**
     * @param id_directory
     * @return A list of RepositoryObjects
     * 
     * @throws KettleException
     */
    public synchronized List<RepositoryObject> getRepositoryObjects(String tableName, String objectType, long id_directory) throws KettleException
    {
        String sql = "SELECT "+quote(Repository.FIELD_TRANSFORMATION_NAME)+", "+quote(Repository.FIELD_TRANSFORMATION_MODIFIED_USER)+", "+quote(Repository.FIELD_TRANSFORMATION_MODIFIED_DATE)+", "+quote(Repository.FIELD_TRANSFORMATION_DESCRIPTION)+" " +
                "FROM "+tableName+" " +
                "WHERE "+quote(Repository.FIELD_TRANSFORMATION_ID_DIRECTORY)+" = " + id_directory + " "
                ;

        List<RepositoryObject> repositoryObjects = new ArrayList<RepositoryObject>();
        
        ResultSet rs = database.openQuery(sql);
        if (rs != null)
        {
        	try
        	{
                Object[] r = database.getRow(rs);
                while (r != null)
                {
                    RowMetaInterface rowMeta = database.getReturnRowMeta();
                    
                    repositoryObjects.add(new RepositoryObject( rowMeta.getString(r, 0), rowMeta.getString(r, 1), rowMeta.getDate(r, 2), objectType, rowMeta.getString(r, 3)));
                    r = database.getRow(rs);
                }
        	}
        	finally 
        	{
        		if ( rs != null )
        		{
        			database.closeQuery(rs);
        		}
        	}                
        }

        return repositoryObjects;
    }
    
    
    public long[] getIDs(String sql) throws KettleException
    {
        List<Long> ids = new ArrayList<Long>();
        
        ResultSet rs = database.openQuery(sql);
        try 
        {
            Object[] r = database.getRow(rs);
            while (r != null)
            {
                RowMetaInterface rowMeta = database.getReturnRowMeta();
                Long id = rowMeta.getInteger(r, 0);
                if (id==null) id=new Long(0);
                
                ids.add(id);
                r = database.getRow(rs);
            }
        }
        finally
        {
        	if ( rs != null )
        	{
        		database.closeQuery(rs);        		
        	}
        }
        return convertLongList(ids);
    }
    
    public String[] getStrings(String sql) throws KettleException
    {
        List<String> ids = new ArrayList<String>();
        
        ResultSet rs = database.openQuery(sql);
        try 
        {
            Object[] r = database.getRow(rs);
            while (r != null)
            {
                RowMetaInterface rowMeta = database.getReturnRowMeta();
                ids.add( rowMeta.getString(r, 0) );
                r = database.getRow(rs);
            }
        }
        finally 
        {
        	if ( rs != null )
        	{
        		database.closeQuery(rs);        		
        	}
        }            

        return (String[]) ids.toArray(new String[ids.size()]);

    }
    
    public static final long[] convertLongList(List<Long> list)
    {
        long[] ids = new long[list.size()];
        for (int i=0;i<ids.length;i++) ids[i] = list.get(i);
        return ids;
    }


    public synchronized void lockRepository() throws KettleException
    {
        if (database.getDatabaseMeta().needsToLockAllTables())
        {
            database.lockTables(Repository.repositoryTableNames);
        }
        else
        {
            database.lockTables( new String[] { Repository.TABLE_R_REPOSITORY_LOG, } );
        }
    }
    
    public synchronized void unlockRepository() throws KettleException
    {
        if (database.getDatabaseMeta().needsToLockAllTables())
        {
            database.unlockTables(Repository.repositoryTableNames);
        }
        else
        {
            database.unlockTables( new String[] { Repository.TABLE_R_REPOSITORY_LOG, } );
        }
    }

	/**
	 * @return the stepAttributesRowMeta
	 */
	public RowMetaInterface getStepAttributesRowMeta() {
		return stepAttributesRowMeta;
	}
	
	public boolean isUseBatchProcessing() {
		return useBatchProcessing;
	}

	/**
	 * @param stepAttributesRowMeta the stepAttributesRowMeta to set
	 */
	public void setStepAttributesRowMeta(RowMetaInterface stepAttributesRowMeta) {
		this.stepAttributesRowMeta = stepAttributesRowMeta;
	}
	
	
	public synchronized long getIDWithValue(String tablename, String idfield, String lookupfield, String value) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
		par.addValue(new ValueMeta("value", ValueMetaInterface.TYPE_STRING), value);
		RowMetaAndData result = getOneRow("SELECT " + idfield + " FROM " + tablename+ " WHERE " + lookupfield + " = ?", par.getRowMeta(), par.getData());

		if (result != null && result.getRowMeta() != null && result.getData() != null && result.isNumeric(0))
			return result.getInteger(0, 0);
		return -1;
	}

	public synchronized long getIDWithValue(String tablename, String idfield, String lookupfield, String value, String lookupkey, long key) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
        par.addValue(new ValueMeta("value", ValueMetaInterface.TYPE_STRING), value);
        par.addValue(new ValueMeta("key", ValueMetaInterface.TYPE_INTEGER), new Long(key));
		RowMetaAndData result = getOneRow("SELECT " + idfield + " FROM " + tablename + " WHERE " + lookupfield + " = ? AND "
									+ lookupkey + " = ?", par.getRowMeta(), par.getData());

		if (result != null && result.getRowMeta() != null && result.getData() != null && result.isNumeric(0))
			return result.getInteger(0, 0);
		return -1;
	}

	public synchronized long getIDWithValue(String tablename, String idfield, String lookupkey[], long key[]) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
		String sql = "SELECT " + idfield + " FROM " + tablename + " ";

		for (int i = 0; i < lookupkey.length; i++)
		{
			if (i == 0)
				sql += "WHERE ";
			else
				sql += "AND   ";
			par.addValue(new ValueMeta(lookupkey[i], ValueMetaInterface.TYPE_INTEGER), new Long(key[i]));
			sql += lookupkey[i] + " = ? ";
		}
		RowMetaAndData result = getOneRow(sql, par.getRowMeta(), par.getData());
		if (result != null && result.getRowMeta() != null && result.getData() != null && result.isNumeric(0))
			return result.getInteger(0, 0);
		return -1;
	}

	public synchronized long getIDWithValue(String tablename, String idfield, String lookupfield, String value, String lookupkey[], long key[]) throws KettleException
	{
		RowMetaAndData par = new RowMetaAndData();
        par.addValue(new ValueMeta(lookupfield, ValueMetaInterface.TYPE_STRING), value);
        
		String sql = "SELECT " + idfield + " FROM " + tablename + " WHERE " + lookupfield + " = ? ";

		for (int i = 0; i < lookupkey.length; i++)
		{
			par.addValue( new ValueMeta(lookupkey[i], ValueMetaInterface.TYPE_STRING), new Long(key[i]) );
			sql += "AND " + lookupkey[i] + " = ? ";
		}

		RowMetaAndData result = getOneRow(sql, par.getRowMeta(), par.getData());
		if (result != null && result.getRowMeta() != null && result.getData() != null && result.isNumeric(0))
			return result.getInteger(0, 0);
		return -1;
	}

	public RowMetaAndData getOneRow(String tablename, String keyfield, long id) throws KettleException
	{
		String sql = "SELECT * FROM " + tablename + " WHERE " + keyfield + " = " + id;

		return getOneRow(sql);
	}

    public RowMetaAndData getOneRow(String sql) throws KettleDatabaseException { 
    	return database.getOneRow(sql);
    }

    public RowMetaAndData getOneRow(String sql, RowMetaInterface rowMeta, Object[] rowData) throws KettleDatabaseException { 
    	return database.getOneRow(sql, rowMeta, rowData);
    }

	public synchronized String getStringWithID(String tablename, String keyfield, long id, String fieldname) throws KettleException
	{
		String sql = "SELECT " + fieldname + " FROM " + tablename + " WHERE " + keyfield + " = ?";
		RowMetaAndData par = new RowMetaAndData();
		par.addValue(new ValueMeta(keyfield, ValueMetaInterface.TYPE_INTEGER), new Long(id));
		RowMetaAndData result = getOneRow(sql, par.getRowMeta(), par.getData());
		if (result != null && result.getData()!=null)
		{
			return result.getString(0, null);
		}
		return null;
	}
	
    public List<Object[]> getRows(String sql, int limit) throws KettleDatabaseException {
    	return database.getRows(sql, limit);
    }

    public RowMetaInterface getReturnRowMeta() throws KettleDatabaseException {
    	return database.getReturnRowMeta();
    }

	public synchronized void insertTableRow(String tablename, RowMetaAndData values) throws KettleException
	{
		database.prepareInsert(values.getRowMeta(), tablename);
		database.setValuesInsert(values);
		database.insertRow();
		database.closeInsert();
	}

}
