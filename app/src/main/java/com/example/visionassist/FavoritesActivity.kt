package com.example.visionassist

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.visionassist.db.AppDatabase
import com.example.visionassist.db.FavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FavoritesActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var rv: RecyclerView

    private val adapter = FavAdapter(
        onNavigate = { fav ->
            // Return result to MainActivity
            val i = Intent().apply {
                putExtra("fav_name", fav.name)
                putExtra("fav_lat", fav.lat)
                putExtra("fav_lon", fav.lon)
            }
            setResult(RESULT_OK, i)
            finish()
        },
        onDelete = { fav ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.favoriteDao().deleteById(fav.id) }
                load()
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        db = AppDatabase.getInstance(this)

        rv = findViewById(R.id.rvFav)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { db.favoriteDao().getAll() }
            adapter.submit(list)
        }
    }

    private class FavAdapter(
        val onNavigate: (FavoriteEntity) -> Unit,
        val onDelete: (FavoriteEntity) -> Unit
    ) : RecyclerView.Adapter<FavVH>() {

        private val items = mutableListOf<FavoriteEntity>()

        fun submit(list: List<FavoriteEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            return FavVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: FavVH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.coord.text = "${item.lat}, ${item.lon}"
            holder.btnNav.setOnClickListener { onNavigate(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    private class FavVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txtName)
        val coord: TextView = v.findViewById(R.id.txtCoord)
        val btnNav: Button = v.findViewById(R.id.btnNav)
        val btnDelete: Button = v.findViewById(R.id.btnDelete)
    }
}
