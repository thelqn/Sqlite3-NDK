package com.thelqn.sample;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.thelqn.sample.model.Message;

import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends Activity {

    MessagesStorage storage;
    ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, android.R.id.text1, new ArrayList<>());

        // To care storage instance
        storage = MessagesStorage.getInstance();

        initUi();
        loadMessages();

    }

    private void initUi() {

        Button output = findViewById(R.id.send);
        EditText input = findViewById(R.id.input);
        ListView message = findViewById(R.id.messages);

        message.setAdapter(adapter);

        output.setOnClickListener(v -> {
            if (input.getText().toString().isEmpty())
                return;
            storage.insertMessage(new Message(UUID.randomUUID().toString(), input.getText().toString()));
            loadMessages();
            input.setText("");


            Bitmap icon = BitmapFactory.decodeResource(getResources(),
                    R.drawable.redheart);

            storage.putWallpapers(icon);
        });
    }

    private void loadMessages() {
        Log.e("Timer", "Data is loading... " + System.currentTimeMillis());
        storage.loadMessagesData(messages -> {
            adapter.clear();
            for (int i = 0; i < messages.size(); i++)
                adapter.add(messages.get(i).getMessage());

            adapter.notifyDataSetChanged();
            Log.e("Timer", "Data is showing... " + System.currentTimeMillis());
        });
    }
}