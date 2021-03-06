/*
 * MIT License
 *
 * Copyright (c) 2016 Kartik Sharma
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.crazyhitty.chdev.ks.predator.core.posts;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.support.annotation.NonNull;

import com.crazyhitty.chdev.ks.predator.MainApplication;
import com.crazyhitty.chdev.ks.predator.R;
import com.crazyhitty.chdev.ks.predator.data.PredatorContract;
import com.crazyhitty.chdev.ks.predator.models.Post;
import com.crazyhitty.chdev.ks.predator.ui.widget.PredatorPostsWidgetProvider;
import com.crazyhitty.chdev.ks.predator.utils.CoreUtils;
import com.crazyhitty.chdev.ks.predator.utils.CursorUtils;
import com.crazyhitty.chdev.ks.predator.utils.DateUtils;
import com.crazyhitty.chdev.ks.predator.utils.Logger;
import com.crazyhitty.chdev.ks.producthunt_wrapper.models.PostsData;
import com.crazyhitty.chdev.ks.producthunt_wrapper.rest.ProductHuntRestApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

/**
 * Author:      Kartik Sharma
 * Email Id:    cr42yh17m4n@gmail.com
 * Created:     1/2/2017 10:13 PM
 * Description: Unavailable
 */

public class PostsPresenter implements PostsContract.Presenter {
    private static final String TAG = "PostsPresenter";

    private String mLastDate = DateUtils.getPredatorCurrentDate();

    private HashMap<Integer, String> mDateHashMap = new HashMap<>();

    private boolean mLoadMore = false;

    @NonNull
    private PostsContract.View mView;

    private CompositeSubscription mCompositeSubscription;

    public PostsPresenter(@NonNull PostsContract.View view) {
        this.mView = view;
        mCompositeSubscription = new CompositeSubscription();
    }

    @Override
    public void subscribe() {

    }

    @Override
    public void unSubscribe() {
        mCompositeSubscription.clear();
    }

    @Override
    public void getOfflinePosts(boolean latest) {
        Observable<List<Post>> postsDataObservable = Observable.create(new Observable.OnSubscribe<List<Post>>() {
            @Override
            public void call(Subscriber<? super List<Post>> subscriber) {
                Cursor cursor = MainApplication.getContentResolverInstance()
                        .query(PredatorContract.PostsEntry.CONTENT_URI_POSTS,
                                null,
                                PredatorContract.PostsEntry.COLUMN_IS_IN_COLLECTION + "=0",
                                null,
                                PredatorContract.PostsEntry.COLUMN_CREATED_AT_MILLIS + " DESC");
                if (cursor != null && cursor.getCount() != 0) {
                    dateMatcher(cursor);
                    subscriber.onNext(getPostsFromCursor(cursor));
                    cursor.close();
                } else {
                    subscriber.onError(new NoPostsAvailableException());
                }
                subscriber.onCompleted();
            }
        });
        postsDataObservable.subscribeOn(Schedulers.io());
        postsDataObservable.observeOn(AndroidSchedulers.mainThread());

        mCompositeSubscription.add(postsDataObservable.subscribe(new Observer<List<Post>>() {
            @Override
            public void onCompleted() {
                // Done
            }

            @Override
            public void onError(Throwable e) {
                Logger.e(TAG, "onError: " + e.getMessage(), e);
                mView.unableToGetPosts(false, true, e.getMessage());
            }

            @Override
            public void onNext(List<Post> posts) {
                mView.showPosts(posts, mDateHashMap);
            }
        }));
    }

    @Override
    public void getPosts(final String token,
                         final String categoryName,
                         final boolean latest,
                         final boolean clearPrevious) {
        Logger.d(TAG, "getPosts: called");
        if (clearPrevious) {
            mLoadMore = false;
            mLastDate = DateUtils.getPredatorCurrentDate();
            mDateHashMap = new HashMap<>();
        }
        Observable<List<Post>> postsDataObservable = ProductHuntRestApi.getApi()
                .getPostsCategoryWise(CoreUtils.getAuthToken(token), categoryName, mLastDate)
                .map(new Func1<PostsData, List<Post>>() {
                    @Override
                    public List<Post> call(PostsData postsData) {
                        if (clearPrevious) {
                            MainApplication.getContentResolverInstance()
                                    .delete(PredatorContract.PostsEntry.CONTENT_URI_POSTS_DELETE,
                                            null,
                                            null);
                            MainApplication.getContentResolverInstance()
                                    .delete(PredatorContract.UsersEntry.CONTENT_URI_USERS_DELETE,
                                            null,
                                            null);
                            MainApplication.getContentResolverInstance()
                                    .delete(PredatorContract.CommentsEntry.CONTENT_URI_COMMENTS_DELETE,
                                            null,
                                            null);
                            MainApplication.getContentResolverInstance()
                                    .delete(PredatorContract.MediaEntry.CONTENT_URI_MEDIA_DELETE,
                                            null,
                                            null);
                        }

                        if (postsData.getPosts() == null || postsData.getPosts().isEmpty()) {
                            return null;
                        }

                        for (PostsData.Posts post : postsData.getPosts()) {
                            Logger.d(TAG, "post: " + post.getName());
                            MainApplication.getContentResolverInstance()
                                    .insert(PredatorContract.PostsEntry.CONTENT_URI_POSTS_ADD,
                                            getContentValuesForPosts(post));

                            // Add/update users.
                            MainApplication.getContentResolverInstance()
                                    .insert(PredatorContract.UsersEntry.CONTENT_URI_USERS_ADD,
                                            getContentValuesForHunterUser(post.getId(), post.getUser()));
                            for (PostsData.Posts.Makers maker : post.getMakers()) {
                                MainApplication.getContentResolverInstance()
                                        .insert(PredatorContract.UsersEntry.CONTENT_URI_USERS_ADD,
                                                getContentValuesForMakerUser(post.getId(), maker));
                            }
                        }
                        Cursor cursor = MainApplication.getContentResolverInstance()
                                .query(PredatorContract.PostsEntry.CONTENT_URI_POSTS,
                                        null,
                                        PredatorContract.PostsEntry.COLUMN_IS_IN_COLLECTION + "=0",
                                        null,
                                        PredatorContract.PostsEntry.COLUMN_CREATED_AT_MILLIS + " DESC");

                        List<Post> posts = null;
                        if (cursor != null && cursor.getCount() != 0) {
                            dateMatcher(cursor);
                            posts = getPostsFromCursor(cursor);
                            cursor.close();
                        }
                        return posts;
                    }
                })
                .flatMap(new Func1<List<Post>, Observable<List<Post>>>() {
                    @Override
                    public Observable<List<Post>> call(final List<Post> posts) {
                        return Observable.create(new Observable.OnSubscribe<List<Post>>() {
                            @Override
                            public void call(Subscriber<? super List<Post>> subscriber) {
                                if (posts != null && posts.size() != 0) {
                                    subscriber.onNext(posts);
                                } else {
                                    loadMorePosts(token, categoryName, latest);
                                }
                                subscriber.onCompleted();
                            }
                        });
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());

        mCompositeSubscription.add(postsDataObservable.subscribe(new Observer<List<Post>>() {
            @Override
            public void onCompleted() {
                // Done
            }

            @Override
            public void onError(Throwable e) {
                Logger.e(TAG, "onError: " + e.getMessage(), e);
                mView.unableToGetPosts(mLoadMore, false, e.getMessage());
            }

            @Override
            public void onNext(List<Post> posts) {
                mView.showPosts(posts, mDateHashMap);
            }
        }));
    }

    @Override
    public void loadMorePosts(String token, String categoryName, boolean latest) {
        mLastDate = DateUtils.getPredatorPostPreviousDate(mLastDate);
        mLoadMore = true;
        getPosts(token, categoryName, latest, false);
    }

    @Override
    public void updateWidgets(Context context) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        int appWidgetIds[] = appWidgetManager.getAppWidgetIds(
                new ComponentName(context, PredatorPostsWidgetProvider.class));
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.list_view_posts);
    }

    /**
     * Matches all the post publish dates and create a hashmap for positions and dates wherever the
     * dates are changed in the cursor.
     *
     * @param cursor Cursor containing database values
     */
    private void dateMatcher(Cursor cursor) {
        for (int i = 0; i < cursor.getCount(); i++) {
            cursor.moveToPosition(i);

            // Match post date with current date
            String date = CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_DAY);

            String dateToBeShown = DateUtils.getPredatorPostDate(date);

            if (!mDateHashMap.containsValue(dateToBeShown)) {
                mDateHashMap.put(i, dateToBeShown);
                mLastDate = date;
            }
        }
    }

    private ContentValues getContentValuesForPosts(PostsData.Posts post) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(PredatorContract.PostsEntry.COLUMN_POST_ID, post.getId());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_CATEGORY_ID, post.getCategoryId());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_DAY, post.getDay());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_NAME, post.getName());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_TAGLINE, post.getTagline());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_COMMENT_COUNT, post.getCommentsCount());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_CREATED_AT, post.getCreatedAt());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_CREATED_AT_MILLIS, DateUtils.predatorDateToMillis(post.getCreatedAt()));
        contentValues.put(PredatorContract.PostsEntry.COLUMN_DISCUSSION_URL, post.getDiscussionUrl());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_REDIRECT_URL, post.getRedirectUrl());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_VOTES_COUNT, post.getVotesCount());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_THUMBNAIL_IMAGE_URL, post.getThumbnail().getImageUrl());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_THUMBNAIL_IMAGE_URL_ORIGINAL, post.getThumbnail().getOriginalImageUrl());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_SCREENSHOT_URL_300PX, post.getScreenshotUrl().getValue300px());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_SCREENSHOT_URL_850PX, post.getScreenshotUrl().getValue850px());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_USER_NAME, post.getUser().getName());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_USER_USERNAME, post.getUser().getUsername());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_USER_ID, post.getUser().getId());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_USER_IMAGE_URL_100PX, post.getUser().getImageUrl().getValue100px());
        contentValues.put(PredatorContract.PostsEntry.COLUMN_USER_IMAGE_URL_ORIGINAL, post.getUser().getImageUrl().getOriginal());
        return contentValues;
    }

    private ContentValues getContentValuesForHunterUser(int postId, PostsData.Posts.User user) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(PredatorContract.UsersEntry.COLUMN_USER_ID, user.getId());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_NAME, user.getName());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_USERNAME, user.getUsername());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_IMAGE_URL_100PX, user.getImageUrl().getValue100px());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_IMAGE_URL_ORIGINAL, user.getImageUrl().getOriginal());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_HUNTER_POST_IDS, postId);
        return contentValues;
    }

    private ContentValues getContentValuesForMakerUser(int postId, PostsData.Posts.Makers maker) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(PredatorContract.UsersEntry.COLUMN_USER_ID, maker.getId());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_CREATED_AT, maker.getCreatedAt());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_NAME, maker.getName());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_USERNAME, maker.getUsername());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_IMAGE_URL_100PX, maker.getImageUrlMaker().getValue48px());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_IMAGE_URL_ORIGINAL, maker.getImageUrlMaker().getOriginal());
        contentValues.put(PredatorContract.UsersEntry.COLUMN_MAKER_POST_IDS, postId);
        return contentValues;
    }

    private List<Post> getPostsFromCursor(Cursor cursor) {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < cursor.getCount(); i++) {
            cursor.moveToPosition(i);
            Post post = new Post();
            post.setId(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_ID));
            post.setPostId(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_POST_ID));
            post.setCategoryId(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_CATEGORY_ID));
            post.setDay(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_DAY));
            post.setName(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_NAME));
            post.setTagline(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_TAGLINE));
            post.setCommentCount(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_COMMENT_COUNT));
            post.setCreatedAt(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_CREATED_AT));
            post.setCreatedAtMillis(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_CREATED_AT_MILLIS));
            post.setDiscussionUrl(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_DISCUSSION_URL));
            post.setRedirectUrl(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_REDIRECT_URL));
            post.setVotesCount(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_VOTES_COUNT));
            post.setThumbnailImageUrl(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_THUMBNAIL_IMAGE_URL));
            post.setThumbnailImageUrlOriginal(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_THUMBNAIL_IMAGE_URL_ORIGINAL));
            post.setScreenshotUrl300px(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_SCREENSHOT_URL_300PX));
            post.setScreenshotUrl850px(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_SCREENSHOT_URL_850PX));
            post.setUsername(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_USER_NAME));
            post.setUsernameAlternative(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_USER_USERNAME));
            post.setUserId(CursorUtils.getInt(cursor, PredatorContract.PostsEntry.COLUMN_USER_ID));
            post.setUserImageUrl100px(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_USER_IMAGE_URL_100PX));
            post.setUserImageUrlOriginal(CursorUtils.getString(cursor, PredatorContract.PostsEntry.COLUMN_USER_IMAGE_URL_ORIGINAL));
            posts.add(post);
        }
        return posts;
    }

    public static class NoPostsAvailableException extends Throwable {
        @Override
        public String getMessage() {
            return "No posts available.";
        }
    }
}
