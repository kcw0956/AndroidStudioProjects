package com.example.draw;

import static android.graphics.drawable.GradientDrawable.LINE;

import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.view.View;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;


import android.view.Menu;
import android.view.MenuItem;



public class MainActivity extends AppCompatActivity
{
    final static int Line = 1, CIRCLE = 2;
    static int curShape = LINE;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new MyGraphicView(this));
        setTitle("간단 그림판");
    }
    private static class MyGraphicView extends View{
        public MyGraphicView(Context context){
            super(context);
        }

    }

}
