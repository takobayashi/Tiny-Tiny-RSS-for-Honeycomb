package org.fox.ttrss;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.jsoup.Jsoup;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

public class MainActivity extends Activity {
	private final String TAG = this.getClass().getSimpleName();

	private final static int UPDATE_INITIAL = 1;
	private final static int UPDATE_SEQUENTIAL = 2;
	private final static int UPDATE_OFFLINE = 3;
	
	private final static int INITIAL_OFFSET_MAX = 100;
	
	private SharedPreferences m_prefs;
	private String m_themeName = "";
	private boolean m_feedsOpened = false;
	protected String m_sessionId;
	protected int m_offset = 0;
	protected int m_limit = 25;
	protected int m_maxId = 0;
	protected int m_updateMode = UPDATE_INITIAL;
	
	protected enum SyncStatus { SYNC_INITIAL, SYNC_ONLINE, SYNC_OFFLINE };
	
	protected MenuItem m_syncStatus;

	protected String getSessionId() {
		return m_sessionId;
	}
	
	protected synchronized void setSessionId(String sessionId) {
		m_sessionId = sessionId;
		
		SharedPreferences.Editor editor = m_prefs.edit();
		editor.putString("last_session_id", m_sessionId);	
		editor.commit();
	}
	
	private Timer m_timer;
	private UpdateTask m_updateTask;
	
	private class UpdateTask extends TimerTask {
		@Override
		public void run() {

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					downloadArticles();
				}

			});			
		}		
	};

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
		m_prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());       
		
		if (m_prefs.getString("theme", "THEME_DARK").equals("THEME_DARK")) {
			setTheme(R.style.DarkTheme);
		} else {
			setTheme(R.style.LightTheme);
		}

		m_themeName = m_prefs.getString("theme", "THEME_DARK");
        
		m_sessionId = m_prefs.getString("last_session_id", null);
		
		if (savedInstanceState != null) {
			m_feedsOpened = savedInstanceState.getBoolean("feedsOpened");
			m_sessionId = savedInstanceState.getString("sessionId");
			m_offset = savedInstanceState.getInt("offset");
			m_limit = savedInstanceState.getInt("limit");
			m_updateMode = savedInstanceState.getInt("updateMode");
			m_maxId = savedInstanceState.getInt("maxId");
		}
		
        // allow database to upgrade before we do anything else
		DatabaseHelper dh = new DatabaseHelper(getApplicationContext());
		SQLiteDatabase db = dh.getWritableDatabase();
		
		if (m_updateMode == UPDATE_INITIAL) {
			db.execSQL("DELETE FROM feeds;");
			db.execSQL("DELETE FROM articles;");
		}
		
		db.close();
        
		
        setContentView(R.layout.main);
        
        if (!m_feedsOpened) {
        	Log.d(TAG, "Opening feeds fragment...");
        	
        	FragmentTransaction ft = getFragmentManager().beginTransaction();			
        	FeedsFragment frag = new FeedsFragment();
		
        	ft.setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out);
        	ft.replace(R.id.feeds_container, frag, "FEEDLIST");
        	ft.commit();
        	
        	m_feedsOpened = true;
        }

        scheduleNextUpdate();
    }
    
	@Override
	public void onSaveInstanceState (Bundle out) {
		super.onSaveInstanceState(out);

		out.putBoolean("feedsOpened", m_feedsOpened);
		out.putString("sessionId", m_sessionId);
		out.putInt("offset", m_offset);
		out.putInt("limit", m_limit);
		out.putInt("updateMode", m_updateMode);
		out.putInt("maxId", m_maxId);
	}
    
	@Override
	public void onResume() {
		super.onResume();

		if (!m_prefs.getString("theme", "THEME_DARK").equals(m_themeName)) {
			Intent refresh = new Intent(this, MainActivity.class);
			startActivity(refresh);
			finish();
		}			
	}
    
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		m_timer.cancel();
		m_timer = null;
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);
		
		m_syncStatus = menu.findItem(R.id.sync_status);
		
		switch (m_updateMode) {
		case UPDATE_INITIAL:
	        setSyncStatus(SyncStatus.SYNC_INITIAL);
	        break;
		case UPDATE_SEQUENTIAL:
			setSyncStatus(SyncStatus.SYNC_ONLINE);
			break;
		default:
			setSyncStatus(SyncStatus.SYNC_OFFLINE);
		}		
		
		return true;
	}

	public void setSyncStatus(SyncStatus status) {
		switch (status) {
		case SYNC_INITIAL:
			m_syncStatus.setTitle(R.string.synchronizing);
			m_syncStatus.setIcon(android.R.drawable.presence_online);
			break;
		case SYNC_ONLINE:
			m_syncStatus.setTitle(R.string.online);
			m_syncStatus.setIcon(android.R.drawable.presence_online);
			break;
		case SYNC_OFFLINE:
			m_syncStatus.setTitle(R.string.offline);
			m_syncStatus.setIcon(android.R.drawable.presence_offline);
			break;
		}
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.preferences:
			Intent intent = new Intent(this, PreferencesActivity.class);
			startActivityForResult(intent, 0);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}
	
	private void downloadArticles() {
		ApiRequest task = new ApiRequest(m_sessionId, 
				m_prefs.getString("ttrss_url", null),
				m_prefs.getString("login", null),
				m_prefs.getString("password", null)) {
			@Override
			protected void onPostExecute(JsonElement result) {
				if (result != null && getAuthStatus() == STATUS_OK) {
					try {
						setSessionId(getSessionId());

						int articlesFound = 0;
						
						try {
							JsonArray feeds_object = (JsonArray) result.getAsJsonArray();
							
							Type listType = new TypeToken<List<Article>>() {}.getType();
							List<Article> articles = m_gson.fromJson(feeds_object, listType);
	
							DatabaseHelper dh = new DatabaseHelper(getApplicationContext());
							SQLiteDatabase db = dh.getWritableDatabase();
	
							/* db.execSQL("DELETE FROM articles"); */
							
							SQLiteStatement stmtInsert = db.compileStatement("INSERT INTO articles " +
									"("+BaseColumns._ID+", unread, marked, published, updated, is_updated, title, link, feed_id, tags, content, excerpt) " +
									"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);");
	
							SQLiteStatement stmtUpdate = db.compileStatement("UPDATE articles SET " +
									"unread = ?, marked = ?, published = ?, updated = ?, is_updated = ?, title = ?, link = ?, feed_id = ?, " +
									"tags = ?, content = ?, excerpt = ? WHERE " + BaseColumns._ID + " = ?");
	
							for (Article article : articles) {
								//Log.d(TAG, "Processing article #" + article.id);
								
								m_maxId = Math.max(m_maxId, article.id);
								
								++articlesFound;
								
								Cursor c = db.query("articles", new String[] { BaseColumns._ID } , BaseColumns._ID + "=?", 
										new String[] { String.valueOf(article.id) }, null, null, null);
								
								String excerpt = Jsoup.parse(article.content).text(); 
								
								if (excerpt.length() > 250) {
									excerpt = excerpt.substring(0, 250) + "...";
								}
								
								if (c.getCount() != 0) {
									stmtUpdate.bindLong(1, article.unread ? 1 : 0);
									stmtUpdate.bindLong(2, article.marked ? 1 : 0);
									stmtUpdate.bindLong(3, article.published ? 1 : 0);
									stmtUpdate.bindLong(4, article.updated);
									stmtUpdate.bindLong(5, article.is_updated ? 1 : 0);
									stmtUpdate.bindString(6, article.title);
									stmtUpdate.bindString(7, article.link);
									stmtUpdate.bindLong(8, article.feed_id);
									stmtUpdate.bindString(9, ""); // comma-separated tags
									stmtUpdate.bindString(10, article.content);
									stmtUpdate.bindString(11, excerpt);
									stmtUpdate.bindLong(12, article.id);
									stmtUpdate.execute();
	
								} else {
									//Log.d(TAG, "article not found");
							
									stmtInsert.bindLong(1, article.id);
									stmtInsert.bindLong(2, article.unread ? 1 : 0);
									stmtInsert.bindLong(3, article.marked ? 1 : 0);
									stmtInsert.bindLong(4, article.published ? 1 : 0);
									stmtInsert.bindLong(5, article.updated);
									stmtInsert.bindLong(6, article.is_updated ? 1 : 0);
									stmtInsert.bindString(7, article.title);
									stmtInsert.bindString(8, article.link);
									stmtInsert.bindLong(9, article.feed_id);
									stmtInsert.bindString(10, ""); // comma-separated tags
									stmtInsert.bindString(11, article.content);
									stmtInsert.bindString(12, excerpt);
									stmtInsert.execute();	
								}
								
								c.close();
							}
							
							db.close();
							
							FeedsFragment ff = (FeedsFragment) getFragmentManager().findFragmentByTag("FEEDLIST");
							
							if (ff != null) ff.updateListView();

						} catch (Exception e) {
							e.printStackTrace();
						}
						
						Log.d(TAG, articlesFound + " articles processed");
						
						if (m_updateMode == UPDATE_INITIAL && articlesFound == m_limit && m_offset < INITIAL_OFFSET_MAX) {
							
							m_offset += m_limit;
							
						} else {
							m_offset = 0;
							
							if (m_updateMode == UPDATE_INITIAL) {
								Log.i(TAG, "Switching to sequential mode...");
								
								setSyncStatus(SyncStatus.SYNC_ONLINE);								
								m_updateMode = UPDATE_SEQUENTIAL;
							}
						}

						scheduleNextUpdate();
						
					} catch (Exception e) {
						e.printStackTrace();
					}										
				}
				
			} 
		};
		
		task.execute(new HashMap<String,String>() {   
			{
				put("sid", m_sessionId);
				put("op", "getHeadlines");
				put("feed_id", "-4");
				put("show_content", "1");
				put("limit", String.valueOf(m_limit));
				put("skip", String.valueOf(m_offset));
				put("view_mode", "unread");
				
				if (m_updateMode != UPDATE_INITIAL) {
					put("since_id", String.valueOf(m_maxId));
				}
				
			}			 
		});
	}

	protected void scheduleNextUpdate() {
		
		if (m_updateTask != null) m_updateTask.cancel();
		
        m_updateTask = new UpdateTask();

        if (m_updateMode == UPDATE_INITIAL) {
        	Log.i(TAG, "Scheduling initial update...");
    		m_timer = new Timer("DownloadInitial");
    		m_timer.schedule(m_updateTask, 1000L);
        } else {
        	Log.i(TAG, "Scheduling sequential update...");
    		m_timer = new Timer("DownloadSequential");
    		m_timer.schedule(m_updateTask, 60*1000L);
        }
	}				

}