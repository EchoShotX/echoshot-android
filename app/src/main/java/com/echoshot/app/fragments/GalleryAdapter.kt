import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.echoshot.app.R
import java.io.File

class GalleryAdapter(private val videoPairs: List<Pair<File, File>>) :
    RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    private val displayList: List<Item> = buildDisplayList()

    private fun buildDisplayList(): List<Item> {
        val list = mutableListOf<Item>()
        for ((noZoom, zoom) in videoPairs) {
            list.add(Item.Video(noZoom, isZoom = false)) // 1번: 노줌
            list.add(Item.Video(zoom, isZoom = true))    // 2번: 줌
            list.add(Item.Locked(noZoom))                // 3번: 잠금 썸네일 (노줌 썸네일 활용)
        }
        return list
    }

    sealed class Item {
        data class Video(val file: File, val isZoom: Boolean): Item()
        data class Locked(val baseFile: File): Item()
    }

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnailImageView)
        val lockOverlay: ImageView = view.findViewById(R.id.lockOverlayImageView)
        val dimOverlay: View = view.findViewById(R.id.dimOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.gallery_item, parent, false)
        return GalleryViewHolder(view)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        when (val item = displayList[position]) {
            is Item.Video -> {
                val bitmap: Bitmap? = ThumbnailUtils.createVideoThumbnail(
                    item.file.absolutePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
                holder.thumbnail.setImageBitmap(bitmap)
                holder.thumbnail.visibility = View.VISIBLE
                holder.lockOverlay.visibility = View.GONE
                holder.dimOverlay.visibility = View.GONE

                holder.itemView.setOnClickListener {
                    val context = holder.itemView.context
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        item.file
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }
            }

            is Item.Locked -> {
                val bitmap: Bitmap? = ThumbnailUtils.createVideoThumbnail(
                    item.baseFile.absolutePath,
                    MediaStore.Images.Thumbnails.MINI_KIND
                )
                holder.thumbnail.setImageBitmap(bitmap)
                holder.thumbnail.visibility = View.VISIBLE
                holder.lockOverlay.visibility = View.VISIBLE
                holder.dimOverlay.visibility = View.VISIBLE

                holder.itemView.setOnClickListener {
                    Toast.makeText(
                        holder.itemView.context,
                        "이 콘텐츠는 잠겨 있습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // ✅ 안전을 위해 else 추가 (혹시 sealed class가 아니라면 필수)
            else -> {
                holder.thumbnail.setImageDrawable(null)
                holder.thumbnail.visibility = View.INVISIBLE
                holder.lockOverlay.visibility = View.GONE
                holder.dimOverlay.visibility = View.GONE
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount(): Int = displayList.size
}
