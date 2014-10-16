package com.medisas;

import com.google.gson.annotations.SerializedName;

public class Tweet {

  @SerializedName("created_at")
  public String createdAt;

  @SerializedName("timestamp_ms")
  public long timestamp;

  @SerializedName("id")
  public String id;

  @SerializedName("text")
  public String text;

  @SerializedName("retweet_count")
  public int retweetCount;

  @SerializedName("retweeted")
  public boolean retweeted;

  public boolean isCreatedTweet() {
    return createdAt != null;
  }

  public String toString() {
    return
        ".\ntext:" + text + "\n" +
            "retweet_count:" + retweetCount + "\n" +
            "retweeted" + retweeted + "\n\n";
  }
}
