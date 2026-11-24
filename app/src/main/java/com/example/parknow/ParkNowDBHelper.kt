package com.example.parknow

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ParkNowDBHelper(context: Context) : SQLiteOpenHelper(context, "ParkNowDB", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cola_comandos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                categoria TEXT UNIQUE,
                ruta TEXT,
                descripcion TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE estado_usuario (
                id INTEGER PRIMARY KEY,
                puesto_actual TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cola_comandos")
        db.execSQL("DROP TABLE IF EXISTS estado_usuario")
        onCreate(db)
    }

    fun guardarPuestoLocal(puesto: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
            put("puesto_actual", puesto)
        }
        db.insertWithOnConflict("estado_usuario", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun obtenerPuestoLocal(): String {
        val db = readableDatabase
        db.rawQuery("SELECT puesto_actual FROM estado_usuario WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: "SIN_ASIGNAR"
            }
        }
        return "SIN_ASIGNAR"
    }

    fun encolarComandoOffline(categoria: String, ruta: String, descripcion: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("categoria", categoria)
            put("ruta", ruta)
            put("descripcion", descripcion)
        }
        db.insertWithOnConflict("cola_comandos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun obtenerComandosPendientes(): List<Pair<String, String>> {
        val lista = mutableListOf<Pair<String, String>>()
        val db = readableDatabase
        db.rawQuery("SELECT ruta, descripcion FROM cola_comandos ORDER BY id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                lista.add(Pair(cursor.getString(0), cursor.getString(1)))
            }
        }
        return lista
    }

    fun obtenerCantidadPendientes(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM cola_comandos", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    fun limpiarCola() {
        writableDatabase.execSQL("DELETE FROM cola_comandos")
    }
}