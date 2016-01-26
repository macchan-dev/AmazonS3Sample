package com.macchan_dev.amazons3sample;

import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import rx.Observable;
import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Main
 * Created by macchan.dev on 2015/12/28.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int RESULTCODE_CAMERA = 0;

    private AmazonS3 mS3Client;
    private TransferUtility mTransferUtility;

    private ListView mBucketListView;
    private ArrayAdapter<String> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                createMovieDir();
                startCamera();
            }
        });

        // Initialize the Amazon Cognito credentials provider
        CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(),
                Constants.IDENTITY_POOL_ID, // Identity Pool ID
                Regions.AP_NORTHEAST_1 // Region
        );

        mS3Client = new AmazonS3Client(credentialsProvider);
        mTransferUtility = new TransferUtility(mS3Client, getApplicationContext());

        mBucketListView = (ListView) findViewById(R.id.listView);
        mAdapter = new ArrayAdapter<>(getApplicationContext(), R.layout.key_list_item);
        mBucketListView.setAdapter(mAdapter);

        Observable.just(1)
                .subscribeOn(Schedulers.newThread())
                .map(new Func1<Integer, List<String>>() {
                    @Override
                    public List<String> call(Integer integer) {
                        // S3のキーを全部取得し、Listで返す
                        List<String> keyArray = new ArrayList<>();

                        try {
                            ObjectListing objectListing;
                            do {
                                ListObjectsRequest listObjectsRequest = new ListObjectsRequest().withBucketName(Constants.BUCKET_NAME);
                                objectListing = mS3Client.listObjects(listObjectsRequest);
                                for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries()) {
                                    keyArray.add(objectSummary.getKey());
                                }
                                listObjectsRequest.setMarker(objectListing.getNextMarker());
                            } while (objectListing.isTruncated());
                        } catch (AmazonClientException e) {
                            e.printStackTrace();
                        }

                        return keyArray;
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<List<String>>() {
                    @Override
                    public void onCompleted() {
                        Log.d(TAG, "onCompleted");
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.e(TAG, "onError");
                        e.printStackTrace();
                    }

                    @Override
                    public void onNext(List<String> strings) {
                        Log.d(TAG, "onNext");
                        mAdapter.clear();
                        for (String keyName : strings) {
                            Log.d(TAG, "add:" + keyName);
                            mAdapter.add(keyName);
                        }
                        mAdapter.notifyDataSetChanged();
                    }
                });
    }

    /**
     * 動画を保存するディレクトリを返す
     *
     * @return ディレクトリのFileインスタンス
     */
    private File getMovieDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), Constants.SAVE_DIRECTORY);
    }

    /**
     * 動画を保存するディレクトリを作成する
     */
    private void createMovieDir() {
        File movieDir = getMovieDir();
        if (!movieDir.exists()) {
            movieDir.mkdirs();
        }
    }

    /**
     * カメラを起動する
     */
    private void startCamera() {
        // ファイル名は年月日時間の逆順。パフォーマンスを良くするため。
        // https://docs.aws.amazon.com/ja_jp/AmazonS3/latest/dev/request-rate-perf-considerations.html
        String fileName = DateFormat.format("ssmmkkddMMyyyy", Calendar.getInstance()) + ".mp4";
        File movieFile = new File(getMovieDir(), fileName);
        Intent intentCamera = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intentCamera.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(movieFile));
        startActivityForResult(intentCamera, RESULTCODE_CAMERA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESULTCODE_CAMERA) {
            if (resultCode != RESULT_OK) {
                return;
            }
            try {
                String path = Util.getPath(getApplicationContext(), data.getData());
                scanFile(path);

                File file = new File(path);
                String filename = file.getName();
                Log.d(TAG, filename);

                upload(filename, file);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 動画ファイルのアップロード
     *
     * @param key
     * @param file
     */
    private void upload(final String key, final File file) {
        TransferObserver observer = mTransferUtility.upload(
                Constants.BUCKET_NAME,
                key,
                file
        );
        observer.setTransferListener(new TransferListener() {
            @Override
            public void onStateChanged(int id, TransferState state) {
                Log.d(TAG, "" + id + ":" + state);
                if (state == TransferState.COMPLETED) {
                    Log.d(TAG, "Complete");
                }
            }

            @Override
            public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
                Log.d(TAG, bytesCurrent * 100.0 / bytesTotal + "%");
                if (bytesCurrent == bytesTotal) {
                    Log.d(TAG, "Total bytes:" + bytesTotal);
                }
            }

            @Override
            public void onError(int id, Exception ex) {
                Log.e(TAG, "ERROR");
            }
        });
    }

    /**
     * 保存したファイルをスキャンする
     *
     * @param path スキャンするファイルパス
     */
    private void scanFile(String path) {
        String[] paths = {path};
        MediaScannerConnection.scanFile(getApplicationContext(), paths, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        Log.d(TAG, "scanned:" + path);
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
