package com.ambient.tv.j2v8;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8ArrayBuffer;
import com.eclipsesource.v8.V8Object;
import com.eclipsesource.v8.JavaCallback;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SourceRuntimeTest {
    @Test
    public void sourceBuiltEngineExecutesAndReleasesRepeatedRuntimes() {
        for (int i = 0; i < 3; i++) {
            V8 runtime = V8.createV8Runtime();
            try {
                assertTrue(V8.getV8Version().startsWith("9.3.345.11"));
                assertEquals(42, runtime.executeIntegerScript("6 * 7"));
                assertEquals("hello", runtime.executeStringScript("'he' + 'llo'"));
                assertEquals(6, runtime.executeIntegerScript("JSON.parse('[1,2,3]').reduce((a,b)=>a+b,0)"));
                V8ArrayBuffer buffer = (V8ArrayBuffer) runtime.executeScript("new ArrayBuffer(16)");
                try {
                    assertEquals(16, buffer.capacity());
                } finally {
                    buffer.release();
                }
                V8Object object = (V8Object) runtime.executeScript("({value: 42})");
                try {
                    assertEquals(42, object.getInteger("value"));
                } finally {
                    object.release();
                }
            } finally {
                runtime.release();
            }
        }
        assertEquals(0, V8.getActiveRuntimes());
    }

    @Test
    public void javaCallbackCrossesJniBoundary() {
        V8 runtime = V8.createV8Runtime();
        try {
            runtime.registerJavaMethod((JavaCallback) (receiver, parameters) ->
                    parameters.getInteger(0) + 1, "ambientIncrement");
            assertEquals(42, runtime.executeIntegerScript("ambientIncrement(41)"));
        } finally {
            runtime.release();
        }
    }
}
