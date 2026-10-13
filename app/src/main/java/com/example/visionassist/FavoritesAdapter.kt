package com.example.visionassist

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.visionassist.db.FavoriteEntity

class FavoritesAdapter(
    private val onDelete: (FavoriteEntity) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private val data = ArrayList<FavoriteEntity>()

    fun submit(list: List<FavoriteEntity>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txtName)
        val coords: TextView = v.findViewById(R.id.txtCoords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_favorite, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val fav = data[position]
        holder.name.text = "⭐ ${fav.name}"
        holder.coords.text = "${fav.lat}, ${fav.lon}"

        holder.itemView.setOnLongClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Delete favourite?")
                .setMessage(fav.name)
                .setPositiveButton("Delete") { _, _ -> onDelete(fav) }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }
}
