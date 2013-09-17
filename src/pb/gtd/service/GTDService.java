package pb.gtd.service;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import pb.gtd.AnnotatedCommand;
import pb.gtd.Command;
import pb.gtd.Constants;
import pb.gtd.Item;
import pb.gtd.Tag;
import pb.gtd.Util;
import pb.gtd.service.db.Database;
import pb.gtd.ui.ItemListItem;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateFormat;
import android.util.Log;

public class GTDService extends Service {

	public static GTDService instance;

	private Lock lock;
	private Handler syncHandler;
	private SyncRunner syncRunner;

	ArrayList<Tag> tagList;
	ArrayList<Item> itemList;

	private Database db;

	@Override
	public void onCreate() {
		Log.w("service", "created");
		instance = this;
		lock = new ReentrantLock();

		tagList = new ArrayList<Tag>();
		itemList = new ArrayList<Item>();

		// default tags. need to add.
		tagList.add(new Tag("inbox", 0));
		tagList.add(new Tag("todo", 0));
		tagList.add(new Tag("ref", 0));
		tagList.add(new Tag("someday", 0));
		tagList.add(new Tag("tickler", 0));

		db = new Database(this);

		HandlerThread thread = new HandlerThread("GTD background thread");
		thread.start();

		syncRunner = new SyncRunner(this);
		syncHandler = new Handler(thread.getLooper());

		setupTLS();
		requestSync(0);

		notifyObservers();
	}

	private void setupTLS() {
		syncHandler.post(new Runnable() {
			@Override
			public void run() {
				// Create a trust manager that trusts one static certificate
				TrustManager[] trustStaticCert = new TrustManager[] { new X509TrustManager() {
					public java.security.cert.X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					public void checkClientTrusted(X509Certificate[] certs,
							String authType) {
					}

					public void checkServerTrusted(X509Certificate[] certs,
							String authType) throws CertificateException {
						if (certs.length != 1) {
							throw new CertificateException(
									"no certificate provided");
						}

						if (!Arrays.equals(certs[0].getEncoded(),
								Constants.SERVER_CERT)) {
							throw new CertificateException(
									"invalid certificate provided");
						}
					}
				} };

				// Install the trust manager
				SSLContext sc;
				try {
					sc = SSLContext.getInstance("SSL");
				} catch (NoSuchAlgorithmException e) {
					Log.e("crypto", e.getMessage());
					e.printStackTrace();
					return;
				}
				try {
					sc.init(null, trustStaticCert,
							new java.security.SecureRandom());
				} catch (KeyManagementException e) {
					Log.e("crypto", e.getMessage());
					e.printStackTrace();
					return;
				}
				HttpsURLConnection.setDefaultSSLSocketFactory(sc
						.getSocketFactory());

				// Create static host name verifier
				HostnameVerifier staticHostValid = new HostnameVerifier() {
					public boolean verify(String hostname, SSLSession session) {
						return hostname.equals(Constants.SERVER_HOSTNAME);
					}
				};
				HttpsURLConnection.setDefaultHostnameVerifier(staticHostValid);
			}
		});
	}

	@Override
	public void onDestroy() {
		Log.w("service", "destroyed");
		instance = null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String act = intent.getAction();
			if (act != null && act.equals("wakeup")) {
				requestSync();
			}
		}
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	public void evaluateCommand(Command c, boolean notify) {
		lock.lock();
		c.apply(tagList, itemList);
		lock.unlock();

		if (notify) {
			notifyObservers();
		}
	}

	public void notifyObservers() {
		LocalBroadcastManager.getInstance(this).sendBroadcast(
				new Intent(Constants.ACTION_SYNCDONE));
	}

	public ArrayList<Tag> getTags() {
		ArrayList<Tag> l = new ArrayList<Tag>();
		String today = DateFormat.format("yyyy-MM-dd", new Date()).toString();

		lock.lock();
		Tag inbox = null;
		Tag tickler = null;
		for (Tag t : tagList) {
			Tag tt = new Tag(t);
			l.add(tt);

			if (tt.title.equals("inbox")) {
				inbox = tt;
			} else if (tt.title.equals("tickler")) {
				tickler = tt;
			}
		}

		inbox.count = 0;
		tickler.count = 0;

		for (Item i : itemList) {
			if (Tag.displayTag(i.tag, today).equals("inbox")) {
				inbox.count++;
			} else if (Tag.displayTag(i.tag, today).equals("tickler")) {
				tickler.count++;
			}
		}
		lock.unlock();

		return l;
	}

	public ArrayList<ItemListItem> getItems(String tag) {
		ArrayList<ItemListItem> l = new ArrayList<ItemListItem>();
		String today = DateFormat.format("yyyy-MM-dd", new Date()).toString();

		lock.lock();
		for (Item i : itemList) {
			if (Tag.displayTag(i.tag, today).equals(tag)) {
				l.add(new ItemListItem(i));
			}
		}
		lock.unlock();

		return l;
	}

	public boolean isOnline() {
		ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
		return (networkInfo != null && networkInfo.isConnected());
	}

	/**
	 * request that the next synchronization is run after delay (possibly 0).
	 * 
	 * @param delay
	 * @return
	 */
	public void requestSync(long delay) {
		if (getServerLocation().length() == 0) {
			Log.d("service", "no sync server configured.");
			return;
		}

		if (delay == 0) {
			syncHandler.post(syncRunner);
		} else {
			AlarmManager mgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			Intent i = new Intent(this, GTDService.class);
			i.setAction("wakeup");
			mgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + delay,
					PendingIntent.getService(this, 0, i, 0));
		}

	}

	public void requestSync() {
		requestSync(0);
	}

	public Database getDatabase() {
		return db;
	}

	public String getServerLocation() {
		SharedPreferences sp = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		return sp.getString("server", "");
	}

	// public String getServerHostname() {
	// SharedPreferences sp = PreferenceManager
	// .getDefaultSharedPreferences(getApplicationContext());
	// String location = sp.getString("server", "");
	//
	// Uri url = Uri.parse(location);
	// return url.getHost();
	// }

	public String getUserName() {
		SharedPreferences sp = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		return sp.getString("username", "");
	}

	public String getPassword() {
		SharedPreferences sp = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		return sp.getString("password", "");
	}

	public void actionDeleteItem(int num) {
		AnnotatedCommand ac = new AnnotatedCommand((short) 0, 0, 'd',
				Util.encodeNum(num, Constants.NUM_LEN));
		db.insertCommand(ac, true);
	}

	public void deleteTag(int num) {
		AnnotatedCommand ac = new AnnotatedCommand((short) 0, 0, 'D',
				Util.encodeNum(num, Constants.NUM_LEN));
		db.insertCommand(ac, true);
	}

	public void actionSetTitle(int num, String title) {
		AnnotatedCommand ac = new AnnotatedCommand((short) 0, 0, 't',
				Util.encodeNum(num, Constants.NUM_LEN) + " " + title);

		db.insertCommand(ac, true);
	}

	public void actionAddItem(String title, String tag) {
		String num = Util.encodeNum(Util.genNum(Constants.NUM_LEN),
				Constants.NUM_LEN);
		AnnotatedCommand ac = new AnnotatedCommand((short) 0, 0, 't', num + " "
				+ title);

		if (!tag.equals("inbox")) {
			ArrayList<AnnotatedCommand> cmds = new ArrayList<AnnotatedCommand>();
			cmds.add(ac);
			cmds.add(new AnnotatedCommand((short) 0, 0, 'T', num + " " + tag));
			db.insertCommands(cmds, true);
		} else {
			db.insertCommand(ac, true);
		}
	}

	public void actionTagItem(int num, String tag) {
		AnnotatedCommand ac = new AnnotatedCommand((short) 0, 0, 'T',
				Util.encodeNum(num, Constants.NUM_LEN) + " " + tag);
		db.insertCommand(ac, true);
	}

	public String getItemTitle(int num) {
		for (Item i : itemList) {
			if (i.num == num) {
				return i.title;
			}
		}

		return "";
	}

	public void onSyncError(String message) {
		Intent in = new Intent(Constants.ACTION_SYNCERROR);
		in.putExtra("msg", message);
		LocalBroadcastManager.getInstance(this).sendBroadcast(in);
	}

}
