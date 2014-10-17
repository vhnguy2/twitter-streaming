package com.medisas;

import android.util.Log;

import com.google.gson.annotations.SerializedName;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Tweet {

  private static final String TAG = Tweet.class.getCanonicalName();

  // example: Wed Aug 27 13:08:45 +0000 2008
  private static final SimpleDateFormat df = new SimpleDateFormat("EEE MMMM dd hh:mm:ss Z yyyy");

  @SerializedName("created_at")
  public String createdAt;

  @SerializedName("text")
  public String text;

  @SerializedName("retweet_count")
  public int retweetCount;

  @SerializedName("retweeted")
  public boolean retweeted;

  @SerializedName("retweeted_status")
  public Tweet retweetedStatus;

  public boolean isRetweet() {
    return retweetedStatus != null;
  }

  public boolean isNotStale(long currTime, long windowSize) {
    return getCreationTimestamp() > currTime - windowSize;
  }

  public long getCreationTimestamp() {
    try {
      Date date = df.parse(createdAt);
      return date.getTime();
    } catch (ParseException e) {
      Log.e(TAG, e.getMessage());
    }

    return 0;
  }

  public String toString() {
    return
        ".\ntext:" + text + "\n" +
            "retweet_count:" + retweetCount + "\n" +
            "retweeted" + retweeted + "\n\n";
  }
}
