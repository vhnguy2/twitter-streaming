package com.medisas;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;

import com.google.gson.Gson;

import org.scribe.builder.ServiceBuilder;
import org.scribe.builder.api.TwitterApi;
import org.scribe.exceptions.OAuthException;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class HomeActivity extends Activity {

  private static final String IS_AUTHENTICATED = "isAuthenticated";
  private static final String API_KEY = "yjKt1v505qifQiaw3PuHjsOFe";
  private static final String API_SECRET = "CzLbPZwV3sLTHmJ3hTV6APEv9pexy4u6AQmj8d97PWDNaiPQvm";
  private static final String TWITTER_STREAMING_SEPARATOR = "\r\n";
  private static final long WINDOW_SIZE = 1000*60*5;
  private static final int NUM_TWEETS_TO_SHOW = 10;

  private SharedPreferences mSharedPrefs;

  private boolean mIsActive = false;

  // authentication objects
  private OAuthService mAuthService;
  private Token mAccessToken;

  private TweetAdapter mAdapter;

  // subviews
  private ListView mListView;
  private WebView mWebView;
  private Button mAuthButton;
  private EditText mAuthVerifier;
  private ViewGroup mContainer;

  // tweet data stored in a max-heap implementation
  private PriorityQueue<Tweet> mTweets;

  private AsyncTask<Void, Void, Void> mTwitterThread;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_home);
    mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

    mListView = (ListView) findViewById(R.id.listview);
    mWebView = (WebView) findViewById(R.id.webview);
    mAuthButton = (Button) findViewById(R.id.auth_button);
    mAuthVerifier = (EditText) findViewById(R.id.auth_verifier);
    mContainer = (ViewGroup) findViewById(R.id.auth_container);

    mAdapter = new TweetAdapter(this);
    mListView.setAdapter(mAdapter);
    mTweets = new PriorityQueue<Tweet>(
        10,
        new Comparator<Tweet>() {
          @Override
          public int compare(Tweet lhs, Tweet rhs) {
            return rhs.retweetCount == lhs.retweetCount ? 1 : rhs.retweetCount - lhs.retweetCount;
          }
        }
    );

    mIsActive = true;
    if (!isAuthenticated()) {
      authenticateUser();
    } else {
      loadTweets();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    mIsActive = true;
    if (mAccessToken != null) {
      loadTweets();
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    mTwitterThread.cancel(true);
    mIsActive = false;
  }

  private void loadTweets() {
    final OAuthRequest request =
        new OAuthRequest(Verb.GET, "https://stream.twitter.com/1.1/statuses/sample.json");
    mAuthService.signRequest(mAccessToken, request);

    mTwitterThread = new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        InputStream stream = null;
        try {
          Response response = request.send();
          stream = response.getStream();

          byte[] bytes = new byte[4096];
          Gson gson = new Gson();
          while(stream.available() > 0 && mIsActive) {
            int bytesRead = stream.read(bytes);
            int totalBytesRead = bytesRead;
            String s = new String(bytes, "UTF-8");
            s = s.substring(0, totalBytesRead);
            while (!s.endsWith(TWITTER_STREAMING_SEPARATOR)) {
              bytesRead = stream.read(bytes);
              String continuingStr = new String(bytes, "UTF-8");
              s = s + continuingStr.substring(0, bytesRead);
              totalBytesRead += bytesRead;
            }
            final Tweet tweet = gson.fromJson(s, Tweet.class);
            if (tweet.isCreatedTweet()) {
              // update the UI on the UI thread instead of the background thread
              Log.e("viet", s);
              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  addTweetToHeap(tweet);
                  evictStaleTweets();
                  List<Tweet> topTweets = new ArrayList<Tweet>();
                  for (Tweet tweet : mTweets) {
                    topTweets.add(tweet);
                    if (topTweets.size() == NUM_TWEETS_TO_SHOW) {
                      break;
                    }
                  }
                  mAdapter.setData(topTweets);
                }
              });
            }
          }
        } catch (IOException e) {
          e.printStackTrace();
        } finally {
          if (stream != null) {
            try {
              stream.close();
            } catch (IOException e) {
            }
          }
        }
        return null;
      }
    };

    mTwitterThread.execute();
  }

  private void addTweetToHeap(Tweet newTweet) {
    mTweets.add(newTweet);
  }

  private void evictStaleTweets() {
    List<Tweet> tweetsToEvict = new ArrayList<Tweet>();
    long currTime = System.currentTimeMillis();
    // compute which tweets to evict
    for (Tweet tweet : mTweets) {
      if (tweet.timestamp < currTime - WINDOW_SIZE) {
        tweetsToEvict.add(tweet);
      }
    }

    // perform the eviction
    for (Tweet tweet : tweetsToEvict) {
      mTweets.remove(tweet);
    }
  }

  private void authenticateUser() {
    setupListeners();
    mAuthService = new ServiceBuilder()
        .provider(TwitterApi.SSL.class)
        .apiKey(API_KEY)
        .apiSecret(API_SECRET)
        .build();
    retrieveRequestToken();
  }

  private void retrieveRequestToken() {
    AsyncTask<Void, Void, Token> task = new AsyncTask<Void, Void, Token>() {
      @Override
      protected Token doInBackground(Void... nulls) {
        return mAuthService.getRequestToken();
      }

      @Override
      protected void onPostExecute(final Token token) {
        mAccessToken = token;
        mWebView.setWebViewClient(new WebViewClient());
        mWebView.loadUrl(mAuthService.getAuthorizationUrl(token));
      }
    };

    task.execute();
  }

  private void retrieveAccessToken(final Token requestToken, final Verifier verifier) {
    AsyncTask<Void, Void, Token> task = new AsyncTask<Void, Void, Token>() {
      @Override
      protected Token doInBackground(Void... nulls) {
        try {
          return mAuthService.getAccessToken(requestToken, verifier);
        } catch (OAuthException e) {
          return null;
        }
      }

      @Override
      protected void onPostExecute(final Token token) {
        if (token != null) {
          mAccessToken = token;
          loadTweets();
        }
      }
    };

    task.execute();
  }

  private void setupListeners() {
    mAuthButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        mContainer.setVisibility(View.GONE);
        mListView.setVisibility(View.VISIBLE);
        String verifier = mAuthVerifier.getText().toString();
        retrieveAccessToken(mAccessToken, new Verifier(verifier));
      }
    });
  }

  private boolean isAuthenticated() {
    return mSharedPrefs.getBoolean(IS_AUTHENTICATED, false);
  }
}
