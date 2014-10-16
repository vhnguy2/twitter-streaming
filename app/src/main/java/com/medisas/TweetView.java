package com.medisas;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

public class TweetView extends FrameLayout {

  private TextView mText;
  private TextView mRetweetCount;

  public TweetView(Context context) {
    this(context, null, 0);
  }

  public TweetView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public TweetView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    LayoutInflater inflater = LayoutInflater.from(context);
    View v = inflater.inflate(R.layout.tweet_view, this);
    mText = (TextView) v.findViewById(R.id.tweet_text);
    mRetweetCount = (TextView) v.findViewById(R.id.tweet_retweet_count);
  }

  public void bindData(Tweet tweet) {
    mText.setText(tweet.text);
    mRetweetCount.setText("" + tweet.retweetCount);
  }
}
