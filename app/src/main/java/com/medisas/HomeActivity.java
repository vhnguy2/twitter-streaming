package com.medisas;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class HomeActivity extends Activity {

  private static final String IS_AUTHENTICATED = "isAuthenticated";
  private static final String API_KEY = "yjKt1v505qifQiaw3PuHjsOFe";
  private static final String API_SECRET = "CzLbPZwV3sLTHmJ3hTV6APEv9pexy4u6AQmj8d97PWDNaiPQvm";
  private static final String TWITTER_STREAMING_SEPARATOR = "\r\n";
  private static final long WINDOW_SIZE = 1000*60*60*24;
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

  // top tweets stored as a min-heap
  private PriorityQueue<Tweet> mTopTweets;
  // other tweets stored as a max-heap
  private PriorityQueue<Tweet> mOtherTweets;

  // hashmap to speed up the searching
  private Map<String, Tweet> mIdToTweet = new HashMap<String, Tweet>();

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
    setupQueues();

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
    if (mTwitterThread != null) {
      mTwitterThread.cancel(true);
    }
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
            final Tweet tweet = parseForNextTweet(stream, bytes, gson);
            if (tweet.isRetweet()) {
              // push more timing information into the child tweet
              tweet.retweetedStatus.lastKnownRetweetedTime = tweet.createdAt;
              if (tweet.retweetedStatus.isNotStale(System.currentTimeMillis(), WINDOW_SIZE) &&
                  addTweetToHeap(tweet.retweetedStatus)) {
                updateListView();
              }
            }
            if (evictStaleTweets()) {
              updateListView();
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

  private void updateListView() {
    // update the UI on the UI thread instead of the background thread
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        List<Tweet> topTweets = new ArrayList<Tweet>();
        for (Tweet tweet : mTopTweets) {
          topTweets.add(tweet);
        }
        Collections.sort(topTweets, new Comparator<Tweet>() {
          @Override
          public int compare(Tweet lhs, Tweet rhs) {
            return rhs.retweetCount - lhs.retweetCount;
          }
        });
        mAdapter.setData(topTweets);
      }
    });
  }

  private Tweet parseForNextTweet(InputStream stream, byte[] bytes, Gson gson)
      throws IOException {
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
    return gson.fromJson(s, Tweet.class);
  }

  /**
   * Adds the tweet into one of the two heaps. Returns true if the Tweet was added to the top heap.
   * Otherwise, false.
   */
  private boolean addTweetToHeap(Tweet newTweet) {
    // since the map and the heaps are pointing to the same reference, an update to the map will
    // also make its way into the heap
    if (!mIdToTweet.containsKey(newTweet.id)) {
      // the original tweet is not stored yet, so add to the heap and then rebalance if needed
      mIdToTweet.put(newTweet.id, newTweet);
      if (mTopTweets.size() < NUM_TWEETS_TO_SHOW) {
        mTopTweets.add(newTweet);
        return true;
      } else if (mTopTweets.peek().retweetCount <= newTweet.retweetCount) {
        Tweet oldTopTweet = mTopTweets.remove();
        mTopTweets.add(newTweet);
        mOtherTweets.add(oldTopTweet);
        return true;
      } else {
        mOtherTweets.add(newTweet);
        return false;
      }
    } else {
      // the original tweet is already stored, so we need to rebalance the heaps if needed
      mIdToTweet.get(newTweet.id).retweetCount = newTweet.retweetCount;
      // reorder the two heaps
      if (!mTopTweets.isEmpty() &&
          !mOtherTweets.isEmpty() &&
          mTopTweets.peek().retweetCount < mOtherTweets.peek().retweetCount) {
        Tweet removedTopTweet = mTopTweets.remove();
        Tweet removedOtherTweet = mOtherTweets.remove();
        mTopTweets.add(removedOtherTweet);
        mOtherTweets.add(removedTopTweet);
        return true;
      }
    }
    return false;
  }

  /**
   * Evicts all stale tweets. In the case where topTweets changes return true, otherwise, false.
   */
  private boolean evictStaleTweets() {
    long currTime = System.currentTimeMillis();
    evictStaleTweets(mOtherTweets, currTime);
    boolean topTweetsChanged = evictStaleTweets(mTopTweets, currTime);
    // max out the topTweets queue using the top-valued nodes in otherTweets
    while (mTopTweets.size() < NUM_TWEETS_TO_SHOW && mOtherTweets.size() > 0) {
      mTopTweets.add(mOtherTweets.remove());
    }

    return topTweetsChanged;
  }

  /**
   * Evicts stale tweets from the list. In the case tweets are evicted return true, otherwise, false
   */
  private boolean evictStaleTweets(PriorityQueue<Tweet> tweets, long currTime) {
    List<Tweet> tweetsToEvict = new ArrayList<Tweet>();
    // compute which tweets to evict
    for (Tweet tweet : tweets) {
      if (tweet.getLastKnownRetweetedTime() < currTime - WINDOW_SIZE) {
        tweetsToEvict.add(tweet);
      }
    }

    // perform the eviction
    for (Tweet tweet : tweetsToEvict) {
      tweets.remove(tweet);
    }

    return !tweetsToEvict.isEmpty();
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

  private void setupQueues() {
    mTopTweets = new PriorityQueue<Tweet>(
        10,
        new Comparator<Tweet>() {
          @Override
          public int compare(Tweet lhs, Tweet rhs) {
            return rhs.retweetCount == lhs.retweetCount ? 1 : lhs.retweetCount - rhs.retweetCount;
          }
        }
    );

    mOtherTweets = new PriorityQueue<Tweet>(
        10,
        new Comparator<Tweet>() {
          @Override
          public int compare(Tweet lhs, Tweet rhs) {
            return rhs.retweetCount == lhs.retweetCount ? 1 : rhs.retweetCount - lhs.retweetCount;
          }
        }
    );
  }

  private boolean isAuthenticated() {
    return mSharedPrefs.getBoolean(IS_AUTHENTICATED, false);
  }
}
