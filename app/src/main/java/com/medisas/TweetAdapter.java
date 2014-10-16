package com.medisas;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.List;

public class TweetAdapter extends BaseAdapter {

  private Context mContext;
  private List<Tweet> mTweets;

  public TweetAdapter(Context context) {
    mContext = context;
    mTweets = new ArrayList<Tweet>();
  }

  public void setData(List<Tweet> tweets) {
    mTweets = new ArrayList<Tweet>(tweets);
    notifyDataSetChanged();
  }

  @Override
  public int getCount() {
    return mTweets.size();
  }

  @Override
  public Object getItem(int position) {
    return mTweets.get(position);
  }

  @Override
  public long getItemId(int position) {
    return position;
  }

  @Override
  public View getView(int position, View convertView, ViewGroup parent) {
    if (convertView == null) {
      convertView = new TweetView(mContext);
    }

    TweetView v = (TweetView) convertView;
    v.bindData((Tweet) getItem(position));
    return v;
  }
}
